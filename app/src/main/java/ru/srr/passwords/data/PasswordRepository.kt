package ru.srr.passwords.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.srr.passwords.util.FaviconResolver
import ru.srr.passwords.util.SecurityAnalyzer
import ru.srr.passwords.util.SiteDisplayName
import ru.srr.passwords.util.VpnConfigStore
import java.io.File
import java.util.UUID

class PasswordRepository private constructor(private val context: Context) {

    private val gson = Gson()
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val mutex = Mutex()
    private val fileLock = Any()

    private val vaultFile: File
        get() = File(context.filesDir, "vault.json.enc")

    private val _state = MutableStateFlow(VaultState())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            deletePlaintextBak()
            val raw = readVault()
            try {
                val loaded = raw.normalize()
                val modelDirty = loaded != raw
                var entries = SecurityAnalyzer.annotate(loaded.entryList())
                val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                val beforePurge = entries.size
                entries = entries.filter { e ->
                    val deletedAt = e.deletedAt ?: return@filter true
                    if (deletedAt >= cutoff) return@filter true
                    runCatching { VpnConfigStore.deleteFile(e) }
                    false
                }
                val purged = beforePurge != entries.size
                val vpnTitles = entries
                    .filter { it.kind == PasswordKind.CONFIG_VPN && it.deletedAt == null }
                    .map { it.title }
                    .toSet()
                val seeded = VpnConfigStore.seedBundled(context, vpnTitles)
                val next = loaded.copy(entries = SecurityAnalyzer.annotate(entries + seeded))
                if (seeded.isNotEmpty() || purged || modelDirty) writeVault(next)
                _state.value = next
            } catch (e: Exception) {
                android.util.Log.e("PasswordsRepo", "load failed", e)
                _state.value = raw
            }
        }
    }

    fun entries(): List<PasswordEntry> = _state.value.entryList()

    fun count(category: HomeCategory): Int = filter(category).size

    fun filter(category: HomeCategory, query: String = ""): List<PasswordEntry> {
        val q = query.trim().lowercase()
        val base = when (category) {
            HomeCategory.ALL -> _state.value.entryList().filter {
                !it.isDeleted() &&
                    it.kind != PasswordKind.CONFIG_VPN &&
                    it.kind != PasswordKind.WIFI
            }
            HomeCategory.PASSKEYS -> _state.value.entryList().filter { !it.isDeleted() && it.hasPasskey() }
            HomeCategory.CODES -> _state.value.entryList().filter { !it.isDeleted() && (it.kind == PasswordKind.CODE || it.otpSecret.isNotBlank()) }
            HomeCategory.SECURITY -> _state.value.entryList().filter { !it.isDeleted() && SecurityAnalyzer.isFlagged(it, _state.value.entryList()) }
            HomeCategory.CONFIG_VPN -> _state.value.entryList().filter { !it.isDeleted() && it.kind == PasswordKind.CONFIG_VPN }
            HomeCategory.DELETED -> _state.value.entryList().filter { it.isDeleted() }
        }
        if (q.isEmpty()) return base.sortedBy { it.title.lowercase() }
        return base.filter {
            it.title.lowercase().contains(q) ||
                it.username.lowercase().contains(q) ||
                it.url.lowercase().contains(q) ||
                it.notes.lowercase().contains(q)
        }.sortedBy { it.title.lowercase() }
    }

    fun find(id: String): PasswordEntry? = _state.value.entryList().find { it.id == id }

    suspend fun upsert(entry: PasswordEntry) = withContext(Dispatchers.IO) {
        if (entry.kind == PasswordKind.WIFI) return@withContext
        mutex.withLock {
            val normalized = entry.copy(
                title = SiteDisplayName.normalizeTitle(entry.title, entry.url, entry.username, entry.kind),
                faviconUrl = entry.faviconUrl ?: FaviconResolver.resolve(entry.url, entry.title, entry.kind)
            ).normalize()
            val list = _state.value.entryList().toMutableList()
            val idx = list.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) list[idx] = normalized else list.add(normalized)
            persist(list)
        }
    }

    suspend fun softDelete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _state.value.entryList().map {
                if (it.id == id) it.copy(deletedAt = System.currentTimeMillis())
                else it
            }
            persist(list)
        }
    }

    suspend fun addAll(newEntries: List<PasswordEntry>) = withContext(Dispatchers.IO) {
        val incoming = newEntries.filter { it.kind != PasswordKind.WIFI }
        if (incoming.isEmpty()) return@withContext
        mutex.withLock {
            val list = _state.value.entryList()
            val prepared = incoming.map { raw ->
                raw.copy(
                    id = raw.id.ifBlank { UUID.randomUUID().toString() },
                    title = SiteDisplayName.normalizeTitle(raw.title, raw.url, raw.username, raw.kind),
                    username = raw.username.ifBlank {
                        if (SiteDisplayName.looksLikeLogin(raw.title)) raw.title else ""
                    },
                    faviconUrl = raw.faviconUrl ?: FaviconResolver.resolve(raw.url, raw.title, raw.kind)
                ).normalize()
            }
            persist(EntryMerge.applyIncoming(list, prepared))
        }
    }

    suspend fun rememberGenerated(password: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prevItems = _state.value.generatedItems.orEmpty().ifEmpty {
                _state.value.generatedHistory.orEmpty().map { GeneratedItem(it, 0L) }
            }
            val next = _state.value.withGenerated(listOf(GeneratedItem(password)) + prevItems)
            writeVault(next)
            _state.value = next.copy(entries = SecurityAnalyzer.annotate(next.entryList()))
        }
    }

    suspend fun replaceAll(entries: List<PasswordEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(entries) }
    }

    private fun persist(entries: List<PasswordEntry>) {
        val annotated = SecurityAnalyzer.annotate(entries.map { it.normalize() })
        val next = _state.value.copy(entries = annotated)
        writeVault(next)
        _state.value = next
    }

    private fun readVault(): VaultState {
        synchronized(fileLock) {
            if (!vaultFile.exists()) return VaultState()
            return try {
                val encrypted = EncryptedFile.Builder(
                    context,
                    vaultFile,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()
                encrypted.openFileInput().use { input ->
                    val json = input.bufferedReader().readText()
                    gson.fromJson(json, VaultState::class.java) ?: VaultState()
                }
            } catch (_: Exception) {
                VaultState()
            }
        }
    }

    private fun writeVault(state: VaultState) {
        synchronized(fileLock) {
            val bytes = gson.toJson(state).toByteArray(Charsets.UTF_8)
            var lastError: Exception? = null
            repeat(5) { attempt ->
                try {
                    if (vaultFile.exists()) {
                        vaultFile.delete()
                    }
                    // EncryptedFile refuses openFileOutput if the path already exists
                    val encrypted = EncryptedFile.Builder(
                        context,
                        vaultFile,
                        masterKey,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                    ).build()
                    encrypted.openFileOutput().use { out -> out.write(bytes) }
                    return
                } catch (e: Exception) {
                    lastError = e
                    try {
                        vaultFile.delete()
                    } catch (_: Exception) {
                    }
                    if (attempt < 4) Thread.sleep(30L * (attempt + 1))
                }
            }
            deletePlaintextBak()
            android.util.Log.e("PasswordsRepo", "writeVault failed", lastError)
        }
    }

    private fun deletePlaintextBak() {
        runCatching { File(context.filesDir, "vault.json.bak").takeIf { it.exists() }?.delete() }
    }

    companion object {
        @Volatile private var instance: PasswordRepository? = null
        fun get(context: Context): PasswordRepository =
            instance ?: synchronized(this) {
                instance ?: PasswordRepository(context.applicationContext).also { instance = it }
            }
    }
}
