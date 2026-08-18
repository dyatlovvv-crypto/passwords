package ru.srr.passwords.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.anyOf
import org.junit.After
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@LargeTest
class VaultFlowUiTest {

    private val mark = "uitest-marker-srr"
    private val appContext get() =
        ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo get() = PasswordRepository.get(appContext)

    @Before
    fun setUp() {
        wakeScreen()
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
    fun addPasswordShowsInAllList() {
        val label = uniqueLabel()
        val user = uniqueUser()
        val intent = android.content.Intent(appContext, PasswordListActivity::class.java)
            .putExtra(PasswordListActivity.EXTRA_CATEGORY, HomeCategory.ALL.name)
        ActivityScenario.launch<PasswordListActivity>(intent).use {
            onView(withId(R.id.btnAdd)).perform(click())
            onView(withId(R.id.inputTitle)).perform(replaceText(label))
            onView(withId(R.id.inputUsername)).perform(replaceText(user))
            onView(withId(R.id.inputPassword)).perform(replaceText("pass-1234"))
            onView(withId(R.id.inputNotes)).perform(replaceText(mark))
            closeSoftKeyboard()
            Thread.sleep(500)
            onView(withId(R.id.btnSave)).perform(click())
            waitUntil("save to vault") { findMarked(user) != null }
            onView(withId(R.id.searchField)).perform(replaceText(user))
            onView(withId(R.id.list)).check(matches(isDisplayed()))
            onView(withId(R.id.list)).check(matches(hasDescendant(withText(user))))
        }
    }

    @Test
    fun deletePasswordMovesOutOfAllList() {
        val user = uniqueUser()
        seed(user)
        val intent = android.content.Intent(appContext, PasswordListActivity::class.java)
            .putExtra(PasswordListActivity.EXTRA_CATEGORY, HomeCategory.ALL.name)
            .putExtra(PasswordListActivity.EXTRA_QUERY, user)
        ActivityScenario.launch<PasswordListActivity>(intent).use {
            onView(withId(R.id.list)).check(matches(hasDescendant(withText(user))))
            onView(withId(R.id.btnMore)).perform(click())
            clickPopup(R.id.rowSelect)
            onView(withId(R.id.list)).perform(
                RecyclerViewActions.actionOnItem<androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                    hasDescendant(withText(user)),
                    click()
                )
            )
            onView(withId(R.id.btnDeleteSelected)).perform(click())
            onView(withId(R.id.btnDelete)).inRoot(RootMatchers.isDialog()).perform(click())
            waitUntil("soft-delete") {
                val entry = findMarked(user)
                entry != null && entry.isDeleted()
            }
            onView(withId(R.id.list)).check(matches(org.hamcrest.Matchers.not(hasDescendant(withText(user)))))
        }
        val deleted = android.content.Intent(appContext, PasswordListActivity::class.java)
            .putExtra(PasswordListActivity.EXTRA_CATEGORY, HomeCategory.DELETED.name)
            .putExtra(PasswordListActivity.EXTRA_QUERY, user)
        ActivityScenario.launch<PasswordListActivity>(deleted).use {
            onView(withId(R.id.list)).check(matches(hasDescendant(withText(user))))
        }
    }

    @Test
    fun exportWritesCsvAndOpensShare() {
        val user = uniqueUser()
        seed(user)
        ActivityScenario.launch(HomeActivity::class.java).use {
            Intents.init()
            Intents.intending(anyOf(hasAction(Intent.ACTION_CHOOSER), hasAction(Intent.ACTION_SEND)))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            onView(withId(R.id.btnMore)).perform(click())
            clickPopup(R.id.rowExport)
            val file = File(appContext.cacheDir, "passwords-export.csv")
            waitUntil("export csv") { file.exists() && file.readText().contains(user) }
            Intents.intended(hasAction(Intent.ACTION_CHOOSER))
        }
    }

    @Test
    fun importFromFileAddsEntry() {
        val user = uniqueUser()
        val label = uniqueLabel()
        val file = File(appContext.cacheDir, "ui-import.csv")
        file.writeText(
            "title,url,username,password,notes,otp,kind\n$label,,$user,secret,$mark,,LOGIN\n"
        )
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        appContext.grantUriPermission(
            appContext.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val result = Intent().setDataAndType(uri, "text/csv")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("import", uri) }
        ActivityScenario.launch(HomeActivity::class.java).use {
            Intents.init()
            Intents.intending(
                anyOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasAction(Intent.ACTION_GET_CONTENT))
            ).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, result))
            onView(withId(R.id.btnMore)).perform(click())
            clickPopup(R.id.rowImport)
            waitUntil("import to vault") { findMarked(user) != null }
        }
        val intent = android.content.Intent(appContext, PasswordListActivity::class.java)
            .putExtra(PasswordListActivity.EXTRA_CATEGORY, HomeCategory.ALL.name)
            .putExtra(PasswordListActivity.EXTRA_QUERY, user)
        ActivityScenario.launch<PasswordListActivity>(intent).use {
            onView(withId(R.id.list)).check(matches(hasDescendant(withText(user))))
        }
    }

    /** Avoid SiteDisplayName brand rewrite (e.mail.ru -> "e", x.com -> "x", t.me -> "t"). */
    private fun uniqueLabel() = "Qqq-${System.nanoTime()}"

    private fun uniqueUser() = "usr-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private fun findMarked(user: String): PasswordEntry? =
        repo.entries().find { it.notes == mark && it.username == user }

    private fun seed(user: String) {
        runBlocking(Dispatchers.IO) {
            repo.load()
            val before = repo.entries().size
            repo.upsert(
                PasswordEntry(
                    id = UUID.randomUUID().toString(),
                    title = uniqueLabel(),
                    username = user,
                    password = "pass-1234",
                    notes = mark,
                    kind = PasswordKind.LOGIN
                )
            )
            val entry = findMarked(user)
            check(entry != null && !entry.isDeleted()) {
                "upsert did not stick before=$before after=${repo.entries().size} titles=${repo.entries().map { it.title }}"
            }
        }
    }

    private fun cleanupMarked() {
        runBlocking(Dispatchers.IO) {
            repo.load()
            repo.entries().filter { it.notes == mark }.forEach { repo.softDelete(it.id) }
        }
    }

    private fun clickPopup(id: Int) {
        Thread.sleep(400)
        onView(withId(id)).inRoot(RootMatchers.isPlatformPopup()).perform(click())
    }

    private fun wakeScreen() {
        val autom = InstrumentationRegistry.getInstrumentation().uiAutomation
        autom.executeShellCommand("input keyevent 224").use { pfd ->
            FileInputStream(pfd.fileDescriptor).copyTo(ByteArrayOutputStream())
        }
        autom.executeShellCommand("svc power stayon true").use { pfd ->
            FileInputStream(pfd.fileDescriptor).copyTo(ByteArrayOutputStream())
        }
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
