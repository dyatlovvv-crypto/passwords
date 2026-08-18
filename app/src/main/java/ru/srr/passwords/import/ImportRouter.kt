package ru.srr.passwords.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.util.VpnConfigStore

object ImportRouter {
    sealed class Outcome {
        data class Passwords(val entries: List<PasswordEntry>) : Outcome()
        data class Vpn(val entry: PasswordEntry) : Outcome()
        data object Empty : Outcome()
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "import.csv"
    }

    fun ingest(context: Context, uri: Uri): Outcome {
        val name = displayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return Outcome.Empty
        if (bytes.isEmpty()) return Outcome.Empty
        return if (CredentialImporter.looksLikeCredentials(name, bytes)) {
            val parsed = CredentialImporter.parse(name, bytes)
            if (parsed.isEmpty()) Outcome.Empty else Outcome.Passwords(parsed)
        } else {
            Outcome.Vpn(VpnConfigStore.importFromUri(context, uri))
        }
    }
}
