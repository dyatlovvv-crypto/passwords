package ru.srr.passwords.data

object EntryMerge {

    fun loginKey(e: PasswordEntry): String =
        "${e.kind}|${e.title.trim().lowercase()}|${e.username.trim().lowercase()}|${e.url.trim().lowercase()}"

    fun applyIncoming(
        existing: List<PasswordEntry>,
        incoming: List<PasswordEntry>
    ): List<PasswordEntry> {
        val list = existing.toMutableList()
        incoming.forEach { entry ->
            val idx = list.indexOfFirst { sameRecord(it, entry) }
            if (idx < 0) {
                list += entry
            } else {
                val prev = list[idx]
                list[idx] = entry.copy(
                    id = prev.id,
                    password = entry.password.ifBlank { prev.password },
                    notes = entry.notes.ifBlank { prev.notes }
                )
            }
        }
        return list
    }

    private fun sameRecord(prev: PasswordEntry, incoming: PasswordEntry): Boolean {
        if (loginKey(prev) != loginKey(incoming)) return false
        if (prev.password.isBlank() || incoming.password.isBlank()) return true
        return prev.password == incoming.password
    }
}
