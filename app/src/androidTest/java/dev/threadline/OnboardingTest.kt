package dev.threadline

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun introductionExplainsTheProductAndContinuesExplicitly() {
        var continued = false
        compose.setContent {
            MaterialTheme {
                OnboardingScreen(onContinue = { continued = true })
            }
        }

        compose.onNodeWithText("Welcome to Threadline").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        compose.onNodeWithText("Transcript first").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        compose.onNodeWithText("Same-session terminal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Direct and verified").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Local by default").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("never passwords or passphrases", substring = true)
            .assertExists()
        compose.onNodeWithText("changed host keys are blocked", substring = true)
            .assertExists()

        compose.onNodeWithTag(OnboardingTags.CONTINUE).performClick()
        compose.runOnIdle { assertTrue(continued) }
    }

    @Test
    fun introductionRemainsNavigableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    OnboardingScreen(onContinue = {})
                }
            }
        }

        compose.onNodeWithTag(OnboardingTags.CONTINUE).assertIsDisplayed()
        compose.onNodeWithText("Local by default").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Transcript first").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completionPersistsWithoutAddingDatabaseState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "onboarding-test-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val onboarding = OnboardingPreferences(preferences)

        assertTrue(onboarding.shouldShowIntroduction())
        onboarding.markIntroductionComplete()
        assertFalse(OnboardingPreferences(preferences).shouldShowIntroduction())
    }
}
