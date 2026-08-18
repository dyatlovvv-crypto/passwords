package ru.srr.passwords

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.ui.AuthActivityCallbacks

class PasswordsApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AuthGate.install(this)
        registerActivityLifecycleCallbacks(AuthActivityCallbacks())
        // Touch ProcessLifecycleOwner early so observer is active
        ProcessLifecycleOwner.get()
        appScope.launch(Dispatchers.IO) {
            runCatching { PasswordRepository.get(this@PasswordsApp).load() }
        }
    }
}
