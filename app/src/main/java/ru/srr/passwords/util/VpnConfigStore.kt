package ru.srr.passwords.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import java.io.File
import java.util.UUID

object VpnConfigStore {
    private const val DIR = "vpn-configs"
    private const val ASSET_DIR = "vpn-configs"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    /** Seed bundled .ovpn from assets once (no duplicates by title). */
    fun seedBundled(context: Context, existingTitles: Set<String>): List<PasswordEntry> {
        val out = mutableListOf<PasswordEntry>()
        val assets = try {
            context.assets.list(ASSET_DIR).orEmpty()
        } catch (_: Exception) {
            emptyArray()
        }
        for (name in assets) {
            if (!name.endsWith(".ovpn", ignoreCase = true) &&
                !name.endsWith(".conf", ignoreCase = true)
            ) continue
            if (existingTitles.any { it.equals(name, ignoreCase = true) }) continue
            try {
                val id = UUID.randomUUID().toString()
                val dest = File(dir(context), "${id}_$name")
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                out += PasswordEntry(
                    id = id,
                    title = name,
                    username = formatSize(dest.length()),
                    password = "",
                    url = "",
                    vpnPath = dest.absolutePath,
                    notes = "VPN config",
                    kind = PasswordKind.CONFIG_VPN,
                    modifiedAt = System.currentTimeMillis()
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun importFromUri(context: Context, uri: Uri): PasswordEntry {
        val resolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        var displayName = "vpn-config.conf"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }

        val id = UUID.randomUUID().toString()
        val safe = displayName.replace(Regex("[^A-Za-z0-9._\\-а-яА-ЯёЁ ]"), "_")
        val dest = File(dir(context), "${id}_$safe")
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Не удалось прочитать файл")

        if (size <= 0L) size = dest.length()

        return PasswordEntry(
            id = id,
            title = displayName,
            username = formatSize(size),
            password = "",
            url = "",
            vpnPath = dest.absolutePath,
            notes = "VPN config",
            kind = PasswordKind.CONFIG_VPN,
            modifiedAt = System.currentTimeMillis()
        )
    }

    fun configPath(entry: PasswordEntry): String {
        if (entry.vpnPath.isNotBlank()) return entry.vpnPath
        if (PasswordEntry.looksLikeFilePath(entry.url)) return entry.url
        return ""
    }

    fun fileFor(entry: PasswordEntry): File? {
        val path = configPath(entry)
        if (path.isBlank()) return null
        val f = File(path)
        return if (f.exists()) f else null
    }

    fun deleteFile(entry: PasswordEntry) {
        val path = configPath(entry)
        if (path.isBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        return "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
