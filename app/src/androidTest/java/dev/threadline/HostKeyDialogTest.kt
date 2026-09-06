package dev.threadline

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
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
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.HostKeyPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HostKeyDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val prompt = HostKeyPrompt(
        endpoint = HostEndpoint("fixture.example", 2222),
        algorithm = "ssh-ed25519",
        fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
    )

    @Test
    fun fingerprintCanBeReadCopiedAndAccepted() {
        var decision: HostKeyDecision? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        compose.setContent {
            MaterialTheme {
                HostKeyDialog(
                    prompt = prompt,
                    onDecision = {
                        decision = it
                        true
                    },
                )
            }
        }

        compose.onNodeWithTag(HostKeyDialogTags.ENDPOINT).assertIsDisplayed()
        compose.onNodeWithText("fixture.example:2222").assertIsDisplayed()
        compose.onNodeWithTag(HostKeyDialogTags.ALGORITHM).assertIsDisplayed()
        compose.onNodeWithText("ssh-ed25519").assertIsDisplayed()
        compose.onNodeWithTag(HostKeyDialogTags.FINGERPRINT).assertIsDisplayed()
        compose.onNodeWithText(prompt.fingerprint).assertIsDisplayed()

        compose.onNodeWithTag(HostKeyDialogTags.COPY).performScrollTo().performClick()
        compose.onNodeWithTag(HostKeyDialogTags.COPY_FEEDBACK)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        compose.runOnIdle {
            assertEquals(
                prompt.fingerprint,
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString(),
            )
        }

        compose.onNodeWithText("Accept and save").performClick()
        compose.runOnIdle { assertEquals(HostKeyDecision.ACCEPT_AND_SAVE, decision) }
    }

    @Test
    fun verificationActionsRemainReachableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    HostKeyDialog(prompt = prompt, onDecision = { true })
                }
            }
        }

        compose.onNodeWithTag(HostKeyDialogTags.FINGERPRINT)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(HostKeyDialogTags.COPY)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Reject").assertIsDisplayed()
        compose.onNodeWithText("Accept and save").assertIsDisplayed()
    }
}
