package ru.srr.passwords.util

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat

object SearchFieldStyle {
    fun apply(field: EditText) {
        field.setBackgroundColor(Color.TRANSPARENT)
        field.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        ViewCompat.setBackgroundTintList(field, ColorStateList.valueOf(Color.TRANSPARENT))
        field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        field.isFocusable = true
        field.isFocusableInTouchMode = true
    }
}
