package ru.srr.passwords.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import ru.srr.passwords.auth.AuthGate

/** Sends user back to LockActivity when the vault session is locked. */
class AuthActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        ru.srr.passwords.util.DisplayRefresh.apply(activity)
    }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {
        if (activity is LockActivity) return
        if (activity is ru.srr.passwords.autofill.AutofillUpdateConfirmActivity) return
        if (activity is ru.srr.passwords.autofill.AutofillSaveOfferActivity) return
        if (activity is ru.srr.passwords.autofill.AutofillPickerActivity) return
        if (activity is VpnTextActivity) return
        if (AuthGate.isUnlocked) return
        activity.startActivity(
            Intent(activity, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
