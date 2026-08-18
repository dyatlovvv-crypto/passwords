package ru.srr.passwords.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultNormalizeTest {
    private val gson = Gson()

    @Test
    fun oldLoginJsonWithoutVpnPathDoesNotCrash() {
        val json = """{"entries":[{"id":"1","title":"Mail","username":"a","password":"secret","kind":"LOGIN"}]}"""
        val raw = gson.fromJson(json, VaultState::class.java)
        val loaded = raw.normalize()
        val entry = loaded.entryList().single()
        assertEquals("Mail", entry.title)
        assertEquals("", entry.vpnPath)
        assertEquals(PasswordKind.LOGIN, entry.kind)
    }

    @Test
    fun passkeyKindBecomesLoginFlag() {
        val json = """{"entries":[{"id":"2","title":"Key","kind":"PASSKEY"}]}"""
        val entry = gson.fromJson(json, VaultState::class.java).normalize().entryList().single()
        assertEquals(PasswordKind.LOGIN, entry.kind)
        assertTrue(entry.hasPasskey())
    }

    @Test
    fun vpnFileUrlMovesToVpnPath() {
        val json =
            """{"entries":[{"id":"3","title":"vpn.ovpn","kind":"CONFIG_VPN","url":"/data/user/0/x/vpn.ovpn"}]}"""
        val entry = gson.fromJson(json, VaultState::class.java).normalize().entryList().single()
        assertEquals("/data/user/0/x/vpn.ovpn", entry.vpnPath)
        assertEquals("", entry.url)
    }

    @Test
    fun packagesSplitFromLegacyCsv() {
        val json =
            """{"entries":[{"id":"4","title":"WB","kind":"LOGIN","appPackage":"com.wildberries.ru, ru.wb"}]}"""
        val entry = gson.fromJson(json, VaultState::class.java).normalize().entryList().single()
        assertEquals(listOf("com.wildberries.ru", "ru.wb"), entry.packageList())
        assertTrue(entry.containsPackage("com.wildberries.ru"))
    }

    @Test
    fun deletedKindGetsDeletedAt() {
        val json = """{"entries":[{"id":"5","title":"Gone","kind":"DELETED","modifiedAt":100}]}"""
        val entry = gson.fromJson(json, VaultState::class.java).normalize().entryList().single()
        assertTrue(entry.isDeleted())
        assertEquals(100L, entry.deletedAt)
    }

    @Test
    fun generatedHistoryDualWrite() {
        val json = """{"entries":[],"generatedHistory":["abc","def"]}"""
        val state = gson.fromJson(json, VaultState::class.java).normalize()
        assertEquals(listOf("abc", "def"), state.generatedPasswords())
        assertEquals(2, state.generatedItems.orEmpty().size)
    }

    @Test
    fun wifiEntriesDropped() {
        val json =
            """{"entries":[{"id":"w","title":"home","kind":"WIFI"},{"id":"1","title":"ok","kind":"LOGIN"}]}"""
        val list = gson.fromJson(json, VaultState::class.java).normalize().entryList()
        assertEquals(1, list.size)
        assertEquals("ok", list.single().title)
    }

    @Test
    fun gsonNullStringsSurviveRoundTrip() {
        val entry = PasswordEntry(id = "n", title = "t").normalize()
        val again = gson.fromJson(gson.toJson(entry), PasswordEntry::class.java).normalize()
        assertEquals("t", again.title)
        assertEquals("", again.vpnPath)
    }
}
