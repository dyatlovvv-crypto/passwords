package ru.srr.passwords.dump

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class VaultDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != VaultDump.ACTION) return
        val pending = goAsync()
        try {
            VaultDump.write(context)
        } finally {
            pending.finish()
        }
    }
}
