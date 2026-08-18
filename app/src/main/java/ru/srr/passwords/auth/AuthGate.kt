package ru.srr.passwords.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Session lock for the vault UI.
 * - Cold start: biometric immediately, no "Снять защиту" screen.
 * - After app minimized (with grace) or phone screen off: require lock screen.
 */
object AuthGate {
    @Volatile var isUnlocked: Boolean = false
        private set

    /** When true, LockActivity shows the unlock button UI. */
    @Volatile var showUnlockButton: Boolean = false
        private set

    @Volatile private var suppressLockUntil: Long = 0L
    @Volatile private var screenOff = false
    @Volatile private var installed = false

    fun unlock() {
        isUnlocked = true
        showUnlockButton = false
        screenOff = false
    }

    fun lock(requireButton: Boolean) {
        isUnlocked = false
        showUnlockButton = requireButton
    }

    /** Call before file pickers / share sheets so brief background does not lock. */
    fun suppressBriefly(ms: Long = 90_000L) {
        suppressLockUntil = System.currentTimeMillis() + ms
    }

    fun install(appContext: Context) {
        if (installed) return
        installed = true
        val app = appContext.applicationContext

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val now = System.currentTimeMillis()
                if (now < suppressLockUntil) return
                // After свертывание — next open shows "Снять защиту"
                lock(requireButton = true)
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOff = true
                        lock(requireButton = true)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // phone unlocked; app still needs its own unlock if was locked
                        if (!isUnlocked) showUnlockButton = true
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }
}
