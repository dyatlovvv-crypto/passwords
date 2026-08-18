package ru.srr.passwords.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.srr.passwords.data.PasswordKind

class CredentialImporterTest {

    @Test
    fun appleCsvGoesToLoginsNotVpn() {
        val bytes = resource("import/apple-passwords.csv")
        assertTrue(CredentialImporter.looksLikeCredentials("Пароли.csv", bytes))
        val parsed = CredentialImporter.parse("Passwords.csv", bytes)
        assertEquals(3, parsed.size)
        assertTrue(parsed.none { it.kind == PasswordKind.CONFIG_VPN })
        assertEquals(2, parsed.count { it.kind == PasswordKind.LOGIN && it.otpSecret.isBlank() })
        assertEquals(1, parsed.count { it.otpSecret.startsWith("otpauth://") })
        assertEquals("usr-a", parsed.first { it.username == "usr-a" }.username)
        assertTrue(parsed.first { it.username == "usr-a" }.url.startsWith("https://"))
    }

    @Test
    fun titleColumnIsNotUsername() {
        val csv = "Title,URL,Username,Password,Notes,OTPAuth\nQqqSiteA,https://a.example,usr-a,secret,,\n"
        val parsed = CredentialImporter.parse("Passwords.csv", csv.toByteArray())
        val e = parsed.single()
        assertEquals("usr-a", e.username)
        assertFalse(e.title.equals("usr-a", ignoreCase = true))
        assertEquals(PasswordKind.LOGIN, e.kind)
    }

    @Test
    fun appExportSplitsByCollectionKind() {
        val parsed = CredentialImporter.parse("passwords-export.csv", resource("import/app-export.csv"))
        assertEquals(PasswordKind.LOGIN, parsed.single { it.username == "usr-a" }.kind)
        assertEquals(PasswordKind.CODE, parsed.single { it.username == "usr-b" }.kind)
        assertEquals(PasswordKind.CONFIG_VPN, parsed.single { it.title.contains("Vpn", ignoreCase = true) }.kind)
        assertTrue(parsed.single { it.username == "usr-k" }.isPasskey)
        assertEquals(PasswordKind.LOGIN, parsed.single { it.username == "usr-k" }.kind)
    }

    @Test
    fun exportRoundtripKeepsCollections() {
        val original = CredentialImporter.parse("app-export.csv", resource("import/app-export.csv"))
        val csv = CredentialExporter.toCsv(original)
        val back = CredentialImporter.parse("round.csv", csv.toByteArray())
        assertEquals(original.count { it.kind == PasswordKind.LOGIN && !it.isPasskey }, back.count { it.kind == PasswordKind.LOGIN && !it.isPasskey })
        assertEquals(original.count { it.kind == PasswordKind.CODE }, back.count { it.kind == PasswordKind.CODE })
        assertEquals(original.count { it.kind == PasswordKind.CONFIG_VPN }, back.count { it.kind == PasswordKind.CONFIG_VPN })
        assertEquals(original.count { it.isPasskey }, back.count { it.isPasskey })
    }

    @Test
    fun ovpnNameIsNotCredentials() {
        assertFalse(CredentialImporter.looksLikeCredentials("office.ovpn", "client\ndev tun\n".toByteArray()))
        assertTrue(CredentialImporter.looksLikeCredentials("Passwords.csv", resource("import/apple-passwords.csv")))
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { path }.readBytes()
}
