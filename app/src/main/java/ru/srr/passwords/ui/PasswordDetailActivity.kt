package ru.srr.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityDetailBinding
import ru.srr.passwords.util.IconLoader
import ru.srr.passwords.util.SecurityAnalyzer
import ru.srr.passwords.util.SiteDisplayName
import ru.srr.passwords.util.VpnConfigStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PasswordDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private lateinit var repo: PasswordRepository
    private var entryId: String = ""
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = PasswordRepository.get(this)
        entryId = intent.getStringExtra(EXTRA_ID).orEmpty()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEdit.setOnClickListener {
            val entry = repo.find(entryId)
            if (entry?.kind == PasswordKind.CONFIG_VPN) {
                openVpnExternalOrInApp()
            } else {
                startActivity(
                    Intent(this, EditPasswordActivity::class.java)
                        .putExtra(EditPasswordActivity.EXTRA_ID, entryId)
                )
            }
        }
        binding.btnShare.setOnClickListener {
            val entry = repo.find(entryId)
            if (entry?.kind == PasswordKind.CONFIG_VPN) shareVpnFile() else shareEntry()
        }
        binding.rowPassword.setOnClickListener {
            val entry = repo.find(entryId) ?: return@setOnClickListener
            if (entry.kind == PasswordKind.CONFIG_VPN) openVpnInApp()
            else {
                passwordVisible = !passwordVisible
                bind()
            }
        }
        binding.rowUsername.setOnClickListener {
            val entry = repo.find(entryId) ?: return@setOnClickListener
            if (entry.kind == PasswordKind.CONFIG_VPN) openVpnInApp()
            else copy(binding.username.text?.toString().orEmpty())
        }
        binding.rowPassword.setOnLongClickListener {
            val entry = repo.find(entryId) ?: return@setOnLongClickListener true
            if (entry.kind == PasswordKind.CONFIG_VPN) shareVpnFile() else copy(entry.password)
            true
        }
        binding.btnChangePassword.setOnClickListener {
            val entry = repo.find(entryId) ?: return@setOnClickListener
            if (entry.kind == PasswordKind.CONFIG_VPN) {
                openVpnInApp()
                return@setOnClickListener
            }
            if (entry.url.isNotBlank()) {
                val url = if (entry.url.startsWith("http")) entry.url else "https://${entry.url}"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } else {
                startActivity(
                    Intent(this, EditPasswordActivity::class.java)
                        .putExtra(EditPasswordActivity.EXTRA_ID, entryId)
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        val entry = repo.find(entryId) ?: run { finish(); return }
        binding.title.text = SiteDisplayName.primary(entry)

        if (entry.kind == PasswordKind.CONFIG_VPN) {
            val file = VpnConfigStore.fileFor(entry)
            binding.btnEdit.text = getString(R.string.vpn_open)
            binding.labelUsername.text = getString(R.string.vpn_file_label)
            binding.labelPassword.text = getString(R.string.vpn_size_label)
            binding.username.text = entry.title
            binding.password.text = entry.username.ifBlank {
                file?.let { VpnConfigStore.formatSize(it.length()) } ?: "—"
            }
            binding.rowPasskey.visibility = View.GONE
            binding.sepPasskey.visibility = View.GONE
            binding.rowWebsite.visibility = View.GONE
            binding.sepWebsite.visibility = View.GONE
            binding.sepAfterWebsite.visibility = View.GONE
            binding.changed.text = formatDate(entry.modifiedAt)
            binding.notes.text = getString(R.string.vpn_config_hint)
            binding.securityCard.visibility = View.VISIBLE
            binding.securityTitle.text = getString(R.string.vpn_config_title)
            binding.securityHint.text = file?.absolutePath ?: getString(R.string.vpn_file_missing)
            binding.btnChangePassword.text = getString(R.string.vpn_open_in_app)
            IconLoader.bind(lifecycleScope, binding.icon, entry)
            return
        }

        binding.btnEdit.text = getString(R.string.edit)
        binding.labelUsername.text = getString(R.string.username)
        binding.labelPassword.text = getString(R.string.password)
        binding.rowWebsite.visibility = View.VISIBLE
        binding.sepWebsite.visibility = View.VISIBLE
        binding.sepAfterWebsite.visibility = View.VISIBLE
        binding.username.text = entry.username.ifBlank { SiteDisplayName.secondary(entry) }
        binding.password.text = if (passwordVisible) entry.password else "••••••••"
        binding.website.text = entry.url.ifBlank {
            if (entry.kind == PasswordKind.WIFI) entry.title else ""
        }
        binding.changed.text = formatDate(entry.modifiedAt)
        binding.notes.text = entry.notes.ifBlank { getString(R.string.notes) }
        binding.securityTitle.text = getString(R.string.reused_password)
        IconLoader.bind(lifecycleScope, binding.icon, entry)

        val hasPasskey = entry.isPasskey || entry.kind == PasswordKind.PASSKEY
        binding.rowPasskey.visibility = if (hasPasskey) View.VISIBLE else View.GONE
        binding.sepPasskey.visibility = if (hasPasskey) View.VISIBLE else View.GONE
        if (hasPasskey) {
            binding.passkeyValue.text = getString(R.string.passkey_created, formatDate(entry.modifiedAt))
        }

        val all = repo.entries()
        val reused = SecurityAnalyzer.reuseCount(entry, all)
        val weak = SecurityAnalyzer.isWeak(entry.password)
        if (reused > 0 || weak) {
            binding.securityCard.visibility = View.VISIBLE
            binding.securityHint.text = when {
                reused > 0 && weak -> "Пароль слабый и используется ещё в $reused записях"
                reused > 0 -> "Этот пароль встречается ещё в $reused записях"
                else -> "Пароль слишком простой"
            }
            binding.btnChangePassword.text = getString(R.string.change_password)
        } else {
            binding.securityCard.visibility = View.GONE
        }
    }

    private fun shareVpnFile() {
        val entry = repo.find(entryId) ?: return
        val file = VpnConfigStore.fileFor(entry)
        if (file == null) {
            Toast.makeText(this, R.string.vpn_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, entry.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.vpn_share)
            )
        )
    }

    private fun openVpnInApp() {
        if (repo.find(entryId) == null) {
            Toast.makeText(this, R.string.vpn_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        AuthGate.suppressBriefly(60_000L)
        startActivity(
            Intent(this, VpnTextActivity::class.java)
                .putExtra(VpnTextActivity.EXTRA_ID, entryId)
        )
    }

    private fun openVpnExternalOrInApp() {
        val entry = repo.find(entryId) ?: return
        val file = VpnConfigStore.fileFor(entry)
        if (file == null) {
            Toast.makeText(this, R.string.vpn_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        AuthGate.suppressBriefly(60_000L)
        val can = view.resolveActivity(packageManager) != null
        if (can) {
            try {
                startActivity(Intent.createChooser(view, getString(R.string.vpn_open_external)))
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.vpn_no_viewer, Toast.LENGTH_SHORT).show()
        openVpnInApp()
    }

    private fun shareEntry() {
        val entry = repo.find(entryId) ?: return
        val text = buildString {
            appendLine(entry.title)
            if (entry.username.isNotBlank()) appendLine("Логин: ${entry.username}")
            if (entry.password.isNotBlank()) appendLine("Пароль: ${entry.password}")
            if (entry.url.isNotBlank()) appendLine("Сайт: ${entry.url}")
        }
        startActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        )
    }

    private fun copy(value: String) {
        if (value.isBlank() || value == "—") return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("passwords", value))
        Toast.makeText(this, R.string.copy, Toast.LENGTH_SHORT).show()
    }

    private fun formatDate(ts: Long): String {
        val fmt = SimpleDateFormat("d MMMM yyyy 'г.'", Locale("ru"))
        return fmt.format(Date(ts))
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
