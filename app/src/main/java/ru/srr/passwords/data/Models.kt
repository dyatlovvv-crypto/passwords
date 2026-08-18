package ru.srr.passwords.data

enum class PasswordKind {
    LOGIN,
    PASSKEY,
    CODE,
    WIFI,
    CONFIG_VPN,
    SECURITY_FLAGGED,
    DELETED
}

data class PasswordEntry(
    val id: String,
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val kind: PasswordKind = PasswordKind.LOGIN,
    val otpSecret: String = "",
    val modifiedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val faviconUrl: String? = null,
    val isPasskey: Boolean = false,
    /** Legacy CSV. Prefer [packages]. Gson may null this. */
    val appPackage: String? = "",
    val packages: List<String>? = emptyList(),
    /** Local VPN config path. Not a website URL. */
    val vpnPath: String = ""
) {
    fun packageList(): List<String> {
        val fromList = packages.orEmpty()
        if (fromList.isNotEmpty()) return fromList
        return parsePackages(appPackage)
    }

    fun packagesSafe(): String = packageList().joinToString(",")

    fun containsPackage(pkg: String): Boolean =
        pkg.isNotBlank() && packageList().any { it.equals(pkg, ignoreCase = true) }

    fun withPackageCsv(csv: String): PasswordEntry {
        val pkgs = parsePackages(csv).distinct()
        return copy(packages = pkgs, appPackage = pkgs.joinToString(","))
    }

    fun isDeleted(): Boolean = deletedAt != null || kind == PasswordKind.DELETED

    fun hasPasskey(): Boolean = isPasskey || kind == PasswordKind.PASSKEY

    fun normalize(): PasswordEntry {
        val nextTitle = gsonString(title as String?)
        val nextUser = gsonString(username as String?)
        val nextPass = gsonString(password as String?)
        val nextNotes = gsonString(notes as String?)
        val nextOtp = gsonString(otpSecret as String?)
        var nextKind = kind
        var nextPasskey = isPasskey || kind == PasswordKind.PASSKEY
        var nextDeletedAt = deletedAt
        var nextUrl = gsonString(url as String?)
        var nextVpn = gsonString(vpnPath as String?)

        if (kind == PasswordKind.PASSKEY) {
            nextKind = PasswordKind.LOGIN
            nextPasskey = true
        }
        if (kind == PasswordKind.DELETED && nextDeletedAt == null) {
            nextDeletedAt = modifiedAt
        }

        if (nextKind == PasswordKind.CONFIG_VPN) {
            if (nextVpn.isBlank() && looksLikeFilePath(nextUrl)) {
                nextVpn = nextUrl
                nextUrl = ""
            }
        } else if (nextVpn.isBlank() && looksLikeFilePath(nextUrl)) {
            nextVpn = nextUrl
        }

        val pkgs = packageList().distinct()
        return copy(
            title = nextTitle,
            username = nextUser,
            password = nextPass,
            notes = nextNotes,
            otpSecret = nextOtp,
            kind = nextKind,
            isPasskey = nextPasskey,
            deletedAt = nextDeletedAt,
            url = nextUrl,
            vpnPath = nextVpn,
            packages = pkgs,
            appPackage = pkgs.joinToString(",")
        )
    }

    companion object {
        fun parsePackages(raw: String?): List<String> =
            (raw ?: "")
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        fun gsonString(value: String?): String = value ?: ""

        fun looksLikeFilePath(value: String?): Boolean {
            val v = gsonString(value)
            if (v.isBlank()) return false
            if (v.startsWith("http://") || v.startsWith("https://")) return false
            return v.startsWith("/") || v.startsWith("file:")
        }
    }
}

data class GeneratedItem(
    val password: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class VaultState(
    val entries: List<PasswordEntry>? = emptyList(),
    val generatedHistory: List<String>? = emptyList(),
    val generatedItems: List<GeneratedItem>? = emptyList()
) {
    fun entryList(): List<PasswordEntry> = entries.orEmpty()

    fun generatedPasswords(): List<String> {
        val items = generatedItems.orEmpty()
        if (items.isNotEmpty()) return items.map { it.password }
        return generatedHistory.orEmpty()
    }

    fun withGenerated(passwords: List<GeneratedItem>): VaultState {
        val items = passwords.distinctBy { it.password }.take(50)
        return copy(
            generatedItems = items,
            generatedHistory = items.map { it.password }
        )
    }

    fun normalize(): VaultState {
        val nextEntries = entries.orEmpty()
            .filter { it.kind != PasswordKind.WIFI }
            .map { it.normalize() }
        val items = if (!generatedItems.isNullOrEmpty()) {
            generatedItems.orEmpty().distinctBy { it.password }.take(50)
        } else {
            generatedHistory.orEmpty().map { GeneratedItem(it, 0L) }.distinctBy { it.password }.take(50)
        }
        return copy(
            entries = nextEntries,
            generatedItems = items,
            generatedHistory = items.map { it.password }
        )
    }
}

enum class HomeCategory {
    ALL, PASSKEYS, CODES, SECURITY, CONFIG_VPN, DELETED
}
