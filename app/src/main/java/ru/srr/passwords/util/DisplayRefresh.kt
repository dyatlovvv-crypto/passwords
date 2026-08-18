package ru.srr.passwords.util

import android.app.Activity
import android.os.Build
import android.view.WindowManager

object DisplayRefresh {
    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        } ?: return
        val best = display.supportedModes.maxByOrNull { it.refreshRate } ?: return
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId == best.modeId) return
        attrs.preferredDisplayModeId = best.modeId
        activity.window.attributes = attrs
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
    }
}
