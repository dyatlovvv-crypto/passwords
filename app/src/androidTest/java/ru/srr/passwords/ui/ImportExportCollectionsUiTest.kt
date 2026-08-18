package ru.srr.passwords.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.anyOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.HomeCategory
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.import.CredentialImporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
@LargeTest
class ImportExportCollectionsUiTest {

    private val mark = "uitest-marker-srr"
    private val appContext get() =
        ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo get() = PasswordRepository.get(appContext)

    @Before
    fun setUp() {
        val autom = InstrumentationRegistry.getInstrumentation().uiAutomation
        autom.executeShellCommand("input keyevent 224").use { pfd ->
            FileInputStream(pfd.fileDescriptor).copyTo(ByteArrayOutputStream())
        }
        AuthGate.unlock()
        AuthGate.suppressBriefly(300_000L)
        cleanupMarked()
    }

    @After
    fun tearDown() {
        runCatching { Intents.release() }
        cleanupMarked()
    }

    @Test
    fun appleCsvMenuImportGoesToAllNotVpn() {
        val uri = liveAsset("import/apple-passwords.csv", "Passwords.csv")
        ActivityScenario.launch(HomeActivity::class.java).use {
            Intents.init()
            Intents.intending(
                anyOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasAction(Intent.ACTION_GET_CONTENT))
            ).respondWith(okUri(uri))
            onView(withId(R.id.btnMore)).perform(click())
            clickMenuRow(R.id.rowImport, R.string.menu_import)
            waitUntil("apple import") {
                marked().count { it.kind != PasswordKind.CONFIG_VPN } >= 3
            }
        }
        assertEquals(0, marked().count { it.kind == PasswordKind.CONFIG_VPN })
        assertTrue(repo.filter(HomeCategory.ALL, "usr-a").any { it.username == "usr-a" })
        openList(HomeCategory.ALL, "usr-a") { onView(withId(R.id.list)).check(matches(hasDescendant(withText("usr-a")))) }
        openList(HomeCategory.CONFIG_VPN, "usr-a") {
            onView(withId(R.id.list)).check(matches(org.hamcrest.Matchers.not(hasDescendant(withText("usr-a")))))
        }
    }

    @Test
    fun csvViaFolderButtonStillParsesCollections() {
        val uri = liveAsset("import/app-export.csv", "passwords-export.csv")
        ActivityScenario.launch(HomeActivity::class.java).use {
            Intents.init()
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(okUri(uri))
            onView(withId(R.id.btnFolder)).perform(click())
            waitUntil("folder csv import") { marked().any { it.username == "usr-a" } }
        }
        assertEquals(PasswordKind.LOGIN, marked().single { it.username == "usr-a" }.kind)
        assertEquals(PasswordKind.CODE, marked().single { it.username == "usr-b" }.kind)
        assertTrue(marked().any { it.kind == PasswordKind.CONFIG_VPN })
        assertTrue(marked().any { it.isPasskey && it.username == "usr-k" })
        openList(HomeCategory.ALL, "usr-a") { onView(withId(R.id.list)).check(matches(hasDescendant(withText("usr-a")))) }
        openList(HomeCategory.CODES, "usr-b") { onView(withId(R.id.list)).check(matches(hasDescendant(withText("usr-b")))) }
        openList(HomeCategory.PASSKEYS, "usr-k") { onView(withId(R.id.list)).check(matches(hasDescendant(withText("usr-k")))) }
        openList(HomeCategory.CONFIG_VPN, "QqqVpnFile") {
            onView(withId(R.id.list)).check(matches(hasDescendant(withText("QqqVpnFile"))))
        }
    }

    @Test
    fun exportLiveFileParsesBackByCollection() {
        val userA = "usr-exp-a-${System.nanoTime()}"
        val userB = "usr-exp-b-${System.nanoTime()}"
        runBlocking(Dispatchers.IO) {
            repo.load()
            repo.upsert(PasswordEntry(id = "t-login", title = "QqqExpA", username = userA, password = "x", notes = mark, kind = PasswordKind.LOGIN))
            repo.upsert(PasswordEntry(id = "t-code", title = "QqqExpB", username = userB, password = "x", notes = mark, otpSecret = "otpauth://totp/x", kind = PasswordKind.CODE))
            repo.upsert(PasswordEntry(id = "t-vpn", title = "QqqExpVpn", notes = mark, kind = PasswordKind.CONFIG_VPN))
        }
        val file = File(appContext.cacheDir, "passwords-export.csv")
        file.delete()
        ActivityScenario.launch(HomeActivity::class.java).use {
            Intents.init()
            Intents.intending(anyOf(hasAction(Intent.ACTION_CHOOSER), hasAction(Intent.ACTION_SEND)))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            onView(withId(R.id.btnMore)).perform(click())
            clickMenuRow(R.id.rowExport, R.string.menu_export)
            waitUntil("export file") { file.exists() && file.readText().contains(userA) }
            val parsed = CredentialImporter.parse(file.name, file.readBytes())
            assertEquals(PasswordKind.LOGIN, parsed.single { it.username == userA }.kind)
            assertEquals(PasswordKind.CODE, parsed.single { it.username == userB }.kind)
            assertEquals(PasswordKind.CONFIG_VPN, parsed.single { it.title == "QqqExpVpn" }.kind)
        }
    }

    private fun liveAsset(assetPath: String, destName: String): android.net.Uri {
        val file = File(appContext.cacheDir, destName)
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetPath).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        appContext.grantUriPermission(appContext.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }

    private fun okUri(uri: android.net.Uri) = Instrumentation.ActivityResult(
        Activity.RESULT_OK,
        Intent().setDataAndType(uri, "text/csv").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
            clipData = ClipData.newRawUri("import", uri)
        }
    )

    private fun openList(cat: HomeCategory, query: String, body: () -> Unit) {
        val intent = Intent(appContext, PasswordListActivity::class.java)
            .putExtra(PasswordListActivity.EXTRA_CATEGORY, cat.name)
            .putExtra(PasswordListActivity.EXTRA_QUERY, query)
        ActivityScenario.launch<PasswordListActivity>(intent).use { body() }
    }

    private fun marked() = repo.entries().filter { it.notes == mark && !it.isDeleted() }

    private fun cleanupMarked() {
        runBlocking(Dispatchers.IO) {
            repo.load()
            repo.entries().filter { it.notes == mark }.forEach { repo.softDelete(it.id) }
        }
    }

    private fun clickMenuRow(id: Int, text: Int) {
        val end = System.currentTimeMillis() + 3_000
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            try {
                onView(withId(id)).inRoot(RootMatchers.isPlatformPopup()).perform(click())
                return
            } catch (t: Throwable) {
                last = t
            }
            try {
                onView(withText(text)).inRoot(RootMatchers.isTouchable()).perform(click())
                return
            } catch (t: Throwable) {
                last = t
            }
            onView(withId(R.id.btnMore)).perform(click())
            Thread.sleep(200)
        }
        throw last ?: AssertionError("menu row not clicked")
    }

    private fun waitUntil(label: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        assertTrue(label, condition())
    }
}
