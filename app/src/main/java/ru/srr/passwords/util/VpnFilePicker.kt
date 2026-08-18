package ru.srr.passwords.util

import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.net.toUri

object VpnFilePicker {
    /**
     * Opens the system file UI at the general start (roots / phone storage),
     * not the last-used subdirectory.
     */
    fun createIntent(): Intent {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            // Force the documents UI onto the primary volume root (общая стартовая),
            // instead of the last opened folder.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val primaryRoot =
                    "content://com.android.externalstorage.documents/document/primary%3A".toUri()
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, primaryRoot)
            }
        }
        return Intent.createChooser(open, "Выберите VPN‑конфиг")
    }
}
