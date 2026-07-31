package dev.threadline

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DiagnosticReportDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reportIsSanitizedByDefaultAndSharesThePreviewedText() {
        var sharedReport: String? = null
        compose.setContent {
            MaterialTheme {
                DiagnosticReportDialog(
                    reportFactory = { includeSensitive ->
                        if (includeSensitive) {
                            "hostname: private.example\ncommand_1_text: private command"
                        } else {
                            "host_profile: redacted\ncommand_content: redacted"
                        }
                    },
                    onDismiss = {},
                    onShare = { sharedReport = it },
                )
            }
        }

        compose.onNodeWithText("host_profile: redacted", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("command_content: redacted", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("private.example", substring = true).assertDoesNotExist()
        assertNull(sharedReport)

        compose.onNodeWithTag(DiagnosticTags.SHARE).performClick()
        compose.runOnIdle {
            assertEquals(
                "host_profile: redacted\ncommand_content: redacted",
                sharedReport,
            )
        }
    }

    @Test
    fun sensitiveDetailsRequireAnExplicitToggleAndRemainVisibleBeforeSharing() {
        var sharedReport: String? = null
        compose.setContent {
            MaterialTheme {
                DiagnosticReportDialog(
                    reportFactory = { includeSensitive ->
                        if (includeSensitive) {
                            "hostname: private.example\ncommand_1_text: private command"
                        } else {
                            "host_profile: redacted"
                        }
                    },
                    onDismiss = {},
                    onShare = { sharedReport = it },
                )
            }
        }

        compose.onNodeWithTag(DiagnosticTags.INCLUDE_SENSITIVE).performClick()
        compose.onNodeWithText("hostname: private.example", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("command_1_text: private command", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(
            "This can expose server names, usernames, paths, and secrets typed inside commands. " +
                "Command output and credentials are still excluded.",
        ).assertIsDisplayed()

        compose.onNodeWithTag(DiagnosticTags.SHARE).performClick()
        compose.runOnIdle {
            assertEquals(
                "hostname: private.example\ncommand_1_text: private command",
                sharedReport,
            )
        }
    }

    @Test
    fun shareFailureIsVisibleAndDoesNotDismissTheReport() {
        compose.setContent {
            MaterialTheme {
                DiagnosticReportDialog(
                    reportFactory = { "sanitized report" },
                    onDismiss = {},
                    onShare = { throw IllegalStateException("No share target") },
                )
            }
        }

        compose.onNodeWithTag(DiagnosticTags.SHARE).performClick()

        compose.onNodeWithText("No app accepted the diagnostic report.")
            .assertIsDisplayed()
        compose.onNodeWithTag(DiagnosticTags.PREVIEW).assertIsDisplayed()
    }

    @Test
    fun shareIntentContainsOnlyThePreviewedPlainText() {
        val intent = diagnosticShareIntent("sanitized report")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Threadline diagnostic report", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("sanitized report", intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
