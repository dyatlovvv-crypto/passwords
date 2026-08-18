package ru.srr.passwords

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.srr.passwords.ui.theme.PasswordsElevation
import ru.srr.passwords.ui.theme.PasswordsRadii
import ru.srr.passwords.ui.theme.PasswordsSpace

@RunWith(AndroidJUnit4::class)
class DesignTokensTest {
    @Test
    fun packageName() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("ru.srr.passwords", ctx.packageName)
    }

    @Test
    fun spacingGrid() {
        assertEquals(4, PasswordsSpace.xs)
        assertEquals(8, PasswordsSpace.sm)
        assertEquals(16, PasswordsSpace.md)
        assertEquals(24, PasswordsSpace.lg)
        assertEquals(32, PasswordsSpace.xl)
        assertEquals(20, PasswordsRadii.card)
        assertEquals(2, PasswordsElevation.card)
    }

    @Test
    fun dimensMatchTokens() {
        val res = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val density = res.displayMetrics.density
        assertEquals(16f, res.getDimension(R.dimen.space_md) / density, 0.1f)
        assertEquals(20f, res.getDimension(R.dimen.radius_card) / density, 0.1f)
        assertEquals(8f, res.getDimension(R.dimen.space_sm) / density, 0.1f)
    }
}
