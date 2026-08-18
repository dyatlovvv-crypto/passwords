package ru.srr.passwords.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityLockBinding
import ru.srr.passwords.util.BiometricGate

class LockActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockBinding
    private var unlocking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AuthGate.isUnlocked) {
            goHome()
            return
        }

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            PasswordRepository.get(this@LockActivity).load()
        }

        val showButton = AuthGate.showUnlockButton
        applyLockUi(showButton)

        binding.btnUnlock.setOnClickListener { unlock() }

        // First launch / cold start: fingerprint immediately, no lock screen chrome
        if (!showButton) {
            binding.root.post { unlock() }
        }
    }

    private fun applyLockUi(showButton: Boolean) {
        if (showButton) {
            binding.root.alpha = 1f
            binding.btnUnlock.visibility = View.VISIBLE
        } else {
            // Keep layout for window, but hide "Снять защиту" content
            binding.root.alpha = 0f
            binding.btnUnlock.visibility = View.INVISIBLE
        }
    }

    private fun unlock() {
        if (unlocking) return
        unlocking = true
        BiometricGate.authenticate(
            activity = this,
            title = getString(ru.srr.passwords.R.string.auth_title),
            subtitle = getString(ru.srr.passwords.R.string.auth_subtitle),
            onSuccess = {
                unlocking = false
                AuthGate.unlock()
                goHome()
            },
            onFail = {
                unlocking = false
                // If silent start failed/cancelled, reveal unlock button
                AuthGate.lock(requireButton = true)
                applyLockUi(true)
                if (it.isNotBlank()) {
                    Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                }
            }
        )
        binding.root.postDelayed({ unlocking = false }, 800)
    }

    private fun goHome() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
