package ru.srr.passwords.import

import ru.srr.passwords.data.PasswordEntry

object CredentialExporter {
    fun toCsv(entries: List<PasswordEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("title,url,username,password,notes,otp,kind,passkey")
        entries.filter { it.deletedAt == null }.forEach { e ->
            sb.appendLine(
                listOf(
                    e.title,
                    e.url,
                    e.username,
                    e.password,
                    e.notes,
                    e.otpSecret,
                    e.kind.name,
                    if (e.isPasskey) "1" else ""
                ).joinToString(",") { csvEscape(it) }
            )
        }
        return sb.toString()
    }

    fun toJson(entries: List<PasswordEntry>): String {
        val items = entries.filter { it.deletedAt == null }.joinToString(",\n") { e ->
            """
            {
              "title": ${jsonStr(e.title)},
              "url": ${jsonStr(e.url)},
              "username": ${jsonStr(e.username)},
              "password": ${jsonStr(e.password)},
              "notes": ${jsonStr(e.notes)},
              "otp": ${jsonStr(e.otpSecret)},
              "kind": ${jsonStr(e.kind.name)},
              "isPasskey": ${e.isPasskey}
            }
            """.trimIndent()
        }
        return "[\n$items\n]"
    }

    private fun csvEscape(s: String): String {
        val needs = s.contains(',') || s.contains('"') || s.contains('\n')
        val escaped = s.replace("\"", "\"\"")
        return if (needs) "\"$escaped\"" else escaped
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
