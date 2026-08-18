package ru.srr.passwords.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryMergeTest {

    @Test
    fun keepsDifferentPasswordsOnSameLogin() {
        val a = login("site", "user", "https://ex.example", "old-secret")
        val b = a.copy(id = "2", password = "new-secret")
        val merged = EntryMerge.applyIncoming(emptyList(), listOf(a, b))
        assertEquals(2, merged.size)
        assertEquals(setOf("old-secret", "new-secret"), merged.map { it.password }.toSet())
    }

    @Test
    fun mergesCaseOnlyUsernameWhenPasswordMatches() {
        val a = login("Яндекс", "Daytelwork", "https://passport.yandex.ru", "same")
        val b = a.copy(id = "2", username = "daytelwork")
        val merged = EntryMerge.applyIncoming(emptyList(), listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("daytelwork", merged.single().username)
    }

    @Test
    fun fillsBlankIncomingPasswordFromExisting() {
        val prev = login("Mail.ru", "u", "https://e.mail.ru", "kept")
        val incoming = prev.copy(id = "2", password = "")
        val merged = EntryMerge.applyIncoming(listOf(prev), listOf(incoming))
        assertEquals(1, merged.size)
        assertEquals("kept", merged.single().password)
        assertEquals(prev.id, merged.single().id)
    }

    private fun login(title: String, user: String, url: String, password: String) = PasswordEntry(
        id = "1",
        title = title,
        username = user,
        password = password,
        url = url,
        kind = PasswordKind.LOGIN
    )
}
