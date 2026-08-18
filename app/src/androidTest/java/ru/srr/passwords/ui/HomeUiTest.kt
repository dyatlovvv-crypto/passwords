package ru.srr.passwords.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.CoreMatchers.allOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.HomeCategory

@RunWith(AndroidJUnit4::class)
@LargeTest
class HomeUiTest {

    @Before
    fun unlockSession() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 224")
        AuthGate.unlock()
        AuthGate.suppressBriefly(300_000L)
    }

    @Test
    fun homeShowsTitleSearchAndPrimaryAdd() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            onView(withId(R.id.title)).check(matches(isDisplayed()))
            onView(withId(R.id.title)).check(matches(withText(R.string.app_name)))
            onView(withId(R.id.categoryGrid)).check(matches(isDisplayed()))
            onView(withId(R.id.searchField)).check(matches(isDisplayed()))
            onView(withId(R.id.btnAdd)).check(matches(isDisplayed()))
            onView(withId(R.id.btnMore)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun listShowsHeaderSearchAndPrimaryAdd() {
        val intent = android.content.Intent(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            PasswordListActivity::class.java
        ).putExtra(PasswordListActivity.EXTRA_CATEGORY, HomeCategory.ALL.name)
        ActivityScenario.launch<PasswordListActivity>(intent).use {
            onView(allOf(withId(R.id.title), isDescendantOfA(withId(R.id.headerTexts))))
                .check(matches(isDisplayed()))
            onView(withId(R.id.list)).check(matches(isDisplayed()))
            onView(withId(R.id.searchField)).check(matches(isDisplayed()))
            onView(withId(R.id.btnAdd)).check(matches(isDisplayed()))
            onView(withId(R.id.btnBack)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun editFormShowsSaveCancelAndFields() {
        ActivityScenario.launch(EditPasswordActivity::class.java).use {
            onView(withId(R.id.btnSave)).check(matches(isDisplayed()))
            onView(withId(R.id.btnCancel)).check(matches(isDisplayed()))
            onView(withId(R.id.inputTitle)).check(matches(isDisplayed()))
            onView(withId(R.id.inputPassword)).check(matches(isDisplayed()))
            onView(withId(R.id.kindSpinner)).check(matches(isDisplayed()))
        }
    }
}
