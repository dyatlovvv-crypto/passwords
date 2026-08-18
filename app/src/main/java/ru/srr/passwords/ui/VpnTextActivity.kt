package ru.srr.passwords.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityVpnTextBinding
import ru.srr.passwords.util.VpnConfigFormatter
import ru.srr.passwords.util.VpnConfigStore
import java.io.File

/** In-app reader for VPN configs with sectioned formatting. */
class VpnTextActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthGate.suppressBriefly(60_000L)
        val binding = ActivityVpnTextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        binding.btnBack.setOnClickListener { finish() }
        binding.content.text = getString(R.string.vpn_loading)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val repo = PasswordRepository.get(this@VpnTextActivity)
                if (repo.entries().isEmpty()) repo.load()
                val entry = repo.find(id)
                val file = entry?.let { VpnConfigStore.fileFor(it) }
                    ?: findByIdPrefix(id)
                when {
                    entry == null && file == null -> null to getString(R.string.vpn_file_missing)
                    file == null || !file.exists() -> {
                        (entry?.title ?: id) to getString(R.string.vpn_file_missing)
                    }
                    else -> (entry?.title ?: file.name) to readSafe(file)
                }
            }
            val (title, body) = result
            if (title == null) {
                Toast.makeText(this@VpnTextActivity, R.string.vpn_file_missing, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            binding.title.text = title
            if (body == getString(R.string.vpn_file_missing)) {
                binding.content.text = body
                Toast.makeText(this@VpnTextActivity, R.string.vpn_file_missing, Toast.LENGTH_SHORT).show()
            } else {
                binding.content.text = VpnConfigFormatter.format(
                    raw = body,
                    keyColor = ContextCompat.getColor(this@VpnTextActivity, R.color.ios_blue),
                    mutedColor = ContextCompat.getColor(this@VpnTextActivity, R.color.ios_text_secondary),
                    headerColor = ContextCompat.getColor(this@VpnTextActivity, R.color.ios_text)
                )
            }
        }
    }

    private fun findByIdPrefix(id: String): File? {
        if (id.isBlank()) return null
        val dir = VpnConfigStore.dir(this)
        return dir.listFiles()?.firstOrNull { it.name.startsWith(id) && it.isFile }
    }

    private fun readSafe(file: File): String = try {
        val text = file.readText(Charsets.UTF_8)
        if (text.isNotBlank()) text else file.readBytes().toString(Charsets.ISO_8859_1)
    } catch (e: Exception) {
        getString(R.string.vpn_read_fail, e.message ?: "")
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
