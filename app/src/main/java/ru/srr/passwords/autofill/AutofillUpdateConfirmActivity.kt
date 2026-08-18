package ru.srr.passwords.autofill

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordRepository

/**
 * Asks before overwriting an existing vault password from Autofill save.
 */
class AutofillUpdateConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthGate.suppressBriefly()

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val newPassword = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE).orEmpty()

        if (entryId.isBlank() || newPassword.isBlank()) {
            finish()
            return
        }

        val site = title.ifBlank { "этот сайт" }
        val loginLine = if (username.isNotBlank()) "\nЛогин: $username" else ""

        AlertDialog.Builder(this)
            .setTitle("Обновить пароль?")
            .setMessage("Для «$site» уже есть сохранённый вход.$loginLine\nЗаменить пароль новым?")
            .setPositiveButton("Обновить") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val repo = PasswordRepository.get(this@AutofillUpdateConfirmActivity)
                        if (repo.entries().isEmpty()) repo.load()
                        val existing = repo.find(entryId) ?: return@withContext
                        repo.upsert(
                            existing.copy(
                                password = newPassword,
                                url = url.ifBlank { existing.url },
                                modifiedAt = System.currentTimeMillis()
                            ).withPackageCsv(appPackage.ifBlank { existing.packagesSafe() })
                        )
                    }
                    finish()
                }
            }
            .setNegativeButton("Не обновлять") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .setCancelable(true)
            .show()
    }

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_URL = "url"
        const val EXTRA_APP_PACKAGE = "app_package"
    }
}
