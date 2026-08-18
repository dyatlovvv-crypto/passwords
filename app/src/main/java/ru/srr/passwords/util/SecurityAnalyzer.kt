package ru.srr.passwords.util

import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind

object SecurityAnalyzer {
    fun annotate(entries: List<PasswordEntry>): List<PasswordEntry> = entries

    fun isFlagged(entry: PasswordEntry, all: List<PasswordEntry>): Boolean {
        if (entry.deletedAt != null) return false
        if (entry.kind == PasswordKind.WIFI || entry.kind == PasswordKind.CONFIG_VPN || entry.password.isBlank()) return false
        if (isWeak(entry.password)) return true
        val same = all.count {
            it.deletedAt == null &&
                it.password.isNotBlank() &&
                it.password == entry.password &&
                it.id != entry.id
        }
        return same > 0
    }

    fun reuseCount(entry: PasswordEntry, all: List<PasswordEntry>): Int {
        if (entry.kind == PasswordKind.WIFI || entry.kind == PasswordKind.CONFIG_VPN) return 0
        if (entry.password.isBlank()) return 0
        return all.count {
            it.deletedAt == null &&
                it.kind != PasswordKind.WIFI &&
                it.kind != PasswordKind.CONFIG_VPN &&
                it.password == entry.password &&
                it.id != entry.id
        }
    }

    fun isWeak(password: String): Boolean {
        if (password.isBlank() || password.length < 8) return password.isNotBlank() && password.length < 8
        val common = setOf("12345678", "password", "qwerty123", "11111111", "123456789")
        return password.lowercase() in common
    }

    /** Red subtitle for «Безопасность» list, iOS-style. */
    fun warningLabel(entry: PasswordEntry, all: List<PasswordEntry>): String? {
        if (!isFlagged(entry, all)) return null
        if (reuseCount(entry, all) > 0) return "reused"
        if (isWeak(entry.password)) return "weak"
        return "flagged"
    }
}
