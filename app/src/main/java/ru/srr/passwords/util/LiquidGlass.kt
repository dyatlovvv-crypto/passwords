package ru.srr.passwords.util

import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import ru.srr.passwords.ui.theme.PasswordsElevation
import ru.srr.passwords.ui.theme.PasswordsRadii

/** Round clip + light float-in. Surfaces stay opaque. */
object LiquidGlass {
    private val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    fun polishBar(view: View, cornerDp: Float = PasswordsRadii.card.toFloat()) {
        val density = view.resources.displayMetrics.density
        val radius = cornerDp * density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineAmbientShadowColor = 0x14000000
            view.outlineSpotShadowColor = 0x1A000000
        }
        view.elevation = PasswordsElevation.card * density
        if (view.alpha >= 0.99f && view.translationY == 0f) {
            view.alpha = 0f
            view.translationY = 8f * density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .setInterpolator(easeOut)
                .start()
        }
    }

    fun polishCard(view: View, cornerDp: Float = PasswordsRadii.card.toFloat()) {
        val density = view.resources.displayMetrics.density
        val radius = cornerDp * density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
        view.elevation = PasswordsElevation.card * density
    }
}
