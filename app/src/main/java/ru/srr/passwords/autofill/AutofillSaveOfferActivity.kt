package ru.srr.passwords.autofill

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.util.AppPackageMatcher
import ru.srr.passwords.util.FaviconResolver
import ru.srr.passwords.util.SiteDisplayName
import java.util.UUID

/**
 * Fallback save/update when system Autofill save sheet never appears.
 * If password was not readable from AssistStructure, asks the user to enter it.
 */
class AutofillSaveOfferActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthGate.suppressBriefly(120_000L)

        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        var password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        val domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty().removePrefix("www.")
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()

        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                val repo = PasswordRepository.get(this@AutofillSaveOfferActivity)
                repo.load()
                if (username.isBlank()) {
                    null
                } else {
                    repo.entries().firstOrNull { e ->
                        e.deletedAt == null &&
                            e.kind == PasswordKind.LOGIN &&
                            e.username.equals(username, ignoreCase = true) &&
                            (
                                (domain.isNotBlank() &&
                                    FaviconResolver.extractHost(e.url)?.removePrefix("www.")
                                        .equals(domain, ignoreCase = true)) ||
                                    (pkg.isNotBlank() && e.containsPackage(pkg))
                                )
                    }
                }
            }

            val title = when {
                domain.isNotBlank() -> domain
                pkg.isNotBlank() ->
                    AppPackageMatcher.brandToken(pkg)?.replaceFirstChar { it.uppercase() } ?: pkg
                else -> "Сохранённый вход"
            }
            val site = if (existing != null) {
                SiteDisplayName.primary(existing).ifBlank { title }
            } else title

            if (existing != null && password.isNotBlank() && existing.password == password) {
                finish()
                return@launch
            }

            val needPasswordInput = password.length < 2
            val passwordInput = if (needPasswordInput) {
                EditText(this@AutofillSaveOfferActivity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = "Пароль"
                    setText(password)
                    setPadding(48, 24, 48, 24)
                }
            } else null

            val isUpdate = existing != null
            val loginLine = if (username.isNotBlank()) "\nЛогин: $username" else ""
            val msg = buildString {
                if (isUpdate) {
                    append("Для «$site» уже есть сохранённый вход.$loginLine")
                    if (needPasswordInput) append("\nВведите новый пароль:")
                    else append("\nЗаменить пароль новым?")
                } else {
                    append("Сохранить вход для «$site»?$loginLine")
                    if (needPasswordInput) append("\nВведите пароль:")
                }
            }

            val container = if (passwordInput != null) {
                FrameLayout(this@AutofillSaveOfferActivity).apply {
                    addView(
                        passwordInput,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            } else null

            val dialog = AlertDialog.Builder(this@AutofillSaveOfferActivity)
                .setTitle(if (isUpdate) "Обновить пароль?" else "Сохранить пароль?")
                .setMessage(msg)
                .setPositiveButton(if (isUpdate) "Обновить" else "Сохранить", null)
                .setNegativeButton("Не сейчас") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create()
            if (container != null) dialog.setView(container)
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val pass = passwordInput?.text?.toString()?.trim().orEmpty().ifBlank { password }
                    if (pass.length < 2) {
                        passwordInput?.error = "Нужен пароль"
                        return@setOnClickListener
                    }
                    password = pass
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            persist(existing, title, username, password, domain, pkg)
                        }
                        dialog.dismiss()
                        finish()
                    }
                }
            }
            dialog.show()
        }
    }

    private suspend fun persist(
        existing: PasswordEntry?,
        title: String,
        username: String,
        password: String,
        domain: String,
        pkg: String
    ) {
        val repo = PasswordRepository.get(this)
        val url = if (domain.isNotBlank()) "https://$domain" else existing?.url.orEmpty()
        val packages = buildString {
            if (pkg.isNotBlank()) append(pkg)
            AppPackageMatcher.packagesForHost(domain).forEach { p ->
                if (isNotEmpty()) append(',')
                if (!contains(p)) append(p)
            }
        }
        if (existing == null) {
            repo.upsert(
                PasswordEntry(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    username = username,
                    password = password,
                    url = url,
                    notes = "Сохранено после входа",
                    kind = PasswordKind.LOGIN,
                    modifiedAt = System.currentTimeMillis()
                ).withPackageCsv(packages)
            )
        } else {
            repo.upsert(
                existing.copy(
                    username = username.ifBlank { existing.username },
                    password = password,
                    url = url.ifBlank { existing.url },
                    modifiedAt = System.currentTimeMillis()
                ).withPackageCsv(packages.ifBlank { existing.packagesSafe() })
            )
        }
    }

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_PACKAGE = "package"
    }
}
