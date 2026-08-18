package ru.srr.passwords.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.srr.passwords.R
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityEditBinding
import ru.srr.passwords.util.AppPackageMatcher
import ru.srr.passwords.util.FaviconResolver
import java.util.UUID

class EditPasswordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEditBinding
    private lateinit var repo: PasswordRepository
    private var entryId: String? = null

    /** Spinner options — PASSKEY means «login with passkey», like iOS. */
    private val kinds = listOf(
        PasswordKind.LOGIN to "Логин",
        PasswordKind.CODE to "Код",
        PasswordKind.PASSKEY to "Ключ входа"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = PasswordRepository.get(this)
        entryId = intent.getStringExtra(EXTRA_ID)

        binding.kindSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            kinds.map { it.second }
        )

        val existing = entryId?.let { repo.find(it) }
        if (existing != null) {
            binding.header.text = getString(R.string.edit)
            binding.inputTitle.setText(existing.title)
            binding.inputUsername.setText(existing.username)
            binding.inputPassword.setText(existing.password)
            binding.inputUrl.setText(existing.url)
            binding.inputAppPackage.setText(existing.packagesSafe())
            binding.inputNotes.setText(existing.notes)
            binding.btnDelete.visibility = View.VISIBLE
            binding.kindSpinner.setSelection(spinnerIndexFor(existing))
        } else {
            val preset = intent.getStringExtra(EXTRA_KIND)
                ?.let { runCatching { PasswordKind.valueOf(it) }.getOrNull() }
            if (preset != null) {
                val idx = kinds.indexOfFirst { it.first == preset }.coerceAtLeast(0)
                binding.kindSpinner.setSelection(idx)
                if (preset == PasswordKind.PASSKEY) {
                    binding.header.text = getString(R.string.passkeys)
                } else if (preset == PasswordKind.CODE) {
                    binding.header.text = getString(R.string.codes)
                }
            }
        }

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener {
            lifecycleScope.launch {
                entryId?.let { repo.softDelete(it) }
                finish()
            }
        }
    }

    private fun spinnerIndexFor(entry: PasswordEntry): Int {
        val want = when {
            entry.isPasskey || entry.kind == PasswordKind.PASSKEY -> PasswordKind.PASSKEY
            entry.kind == PasswordKind.CODE || entry.otpSecret.isNotBlank() -> PasswordKind.CODE
            else -> PasswordKind.LOGIN
        }
        return kinds.indexOfFirst { it.first == want }.coerceAtLeast(0)
    }

    private fun save() {
        val title = binding.inputTitle.text?.toString()?.trim().orEmpty()
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString()?.trim().orEmpty()
        val url = binding.inputUrl.text?.toString()?.trim().orEmpty()
        val notes = binding.inputNotes.text?.toString()?.trim().orEmpty()
        var appPackage = binding.inputAppPackage.text?.toString()?.trim().orEmpty()
        val selected = kinds[binding.kindSpinner.selectedItemPosition].first

        if (title.isBlank() && username.isBlank()) {
            Toast.makeText(this, "Укажите название", Toast.LENGTH_SHORT).show()
            return
        }

        if (appPackage.isBlank() && url.isNotBlank()) {
            val host = FaviconResolver.extractHost(url)
            appPackage = AppPackageMatcher.packagesForHost(host).joinToString(",")
        }

        // Like iOS: passkey is a flag on a login entry, still visible in «Все».
        val kind = when (selected) {
            PasswordKind.PASSKEY -> PasswordKind.LOGIN
            else -> selected
        }
        val isPasskey = selected == PasswordKind.PASSKEY

        val id = entryId ?: UUID.randomUUID().toString()
        val prev = entryId?.let { repo.find(it) }
        val pkgs = PasswordEntry.parsePackages(appPackage)
        val entry = PasswordEntry(
            id = id,
            title = title.ifBlank { username.ifBlank { "Без названия" } },
            username = username,
            password = password,
            url = url,
            notes = notes,
            kind = kind,
            otpSecret = prev?.otpSecret.orEmpty(),
            isPasskey = isPasskey,
            faviconUrl = FaviconResolver.resolve(url, title, kind),
            packages = pkgs,
            appPackage = pkgs.joinToString(","),
            vpnPath = prev?.vpnPath.orEmpty(),
            modifiedAt = System.currentTimeMillis(),
            deletedAt = prev?.deletedAt
        )
        lifecycleScope.launch {
            repo.upsert(entry)
            finish()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"
    }
}
