package dev.threadline

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.CommandTurn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TranscriptScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerSubmitsExactMultilineCommandAndClearsAfterAcceptance() {
        var submitted: String? = null
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(),
                    onSubmit = { command ->
                        submitted = command
                        CommandSubmissionResult.Accepted(CommandId("command-42"))
                    },
                    onStop = {},
                )
            }
        }
        val command = "printf one\nprintf two"

        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .performTextInput(command)
        composeRule.onNodeWithTag(TranscriptTags.SEND)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(command, submitted)
        }
        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(""),
                ),
            )
    }

    @Test
    fun completedTurnShowsCommandOutputAndSemanticStatus() {
        val turn = CommandTurn(
            id = CommandId("command-42"),
            command = "printf hello",
            directoryAtStart = "/srv/app",
            submittedAtMillis = 100L,
            startedAtMillis = 110L,
            completedAtMillis = 145L,
            status = CommandStatus.SUCCEEDED,
            exitStatus = 0,
            currentDirectory = "/srv/app",
            output = CommandOutput(
                plainText = "hello",
                byteCount = 5,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/srv/app"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("rerun"))
                    },
                    onStop = {},
                )
            }
        }

        composeRule.onNodeWithText("printf hello").assertExists()
        composeRule.onNodeWithText("hello").assertExists()
        composeRule.onNodeWithText("Succeeded · /srv/app · 35 ms · exit 0").assertExists()
    }
}
