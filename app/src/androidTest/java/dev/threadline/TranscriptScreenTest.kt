package dev.threadline

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import dev.threadline.core.shell.ActiveCommand
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.LifecyclePhase
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.CommandTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    onDisconnect = {},
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
    fun historyNavigationPreservesMultilineCommandsAndRestoresDraft() {
        val transcript = CommandTranscriptState(
            turns = listOf(
                turn(
                    id = "command-1",
                    command = "printf first",
                    status = CommandStatus.SUCCEEDED,
                ),
                turn(
                    id = "command-2",
                    command = "printf second\nprintf line",
                    status = CommandStatus.SUCCEEDED,
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = transcript,
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .performTextInput("unfinished draft")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_OLDER)
            .assertIsEnabled()
            .performClick()
        assertComposerText("printf second\nprintf line")

        composeRule.onNodeWithTag(TranscriptTags.HISTORY_OLDER)
            .assertIsEnabled()
            .performClick()
        assertComposerText("printf first")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_OLDER).assertIsNotEnabled()

        composeRule.onNodeWithTag(TranscriptTags.HISTORY_NEWER)
            .assertIsEnabled()
            .performClick()
        assertComposerText("printf second\nprintf line")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_NEWER)
            .assertIsEnabled()
            .performClick()
        assertComposerText("unfinished draft")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_NEWER).assertIsNotEnabled()
    }

    @Test
    fun composerAndHistoryPositionSurviveSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(
                        turns = listOf(
                            turn(
                                command = "printf history",
                                status = CommandStatus.SUCCEEDED,
                            ),
                        ),
                    ),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .performTextInput("printf one\nprintf two")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_OLDER).performClick()
        assertComposerText("printf history")
        restorationTester.emulateSavedInstanceStateRestore()

        assertComposerText("printf history")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_NEWER).performClick()
        assertComposerText("printf one\nprintf two")
    }

    @Test
    fun acceptedCardRerunResetsHistoryNavigationWithoutClearingComposer() {
        var submitted: String? = null
        val turn = turn(
            command = "printf history",
            status = CommandStatus.SUCCEEDED,
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = { command ->
                        submitted = command
                        CommandSubmissionResult.Accepted(CommandId("rerun"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .performTextInput("unfinished draft")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_OLDER).performClick()
        composeRule.onNodeWithText("Rerun")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("printf history", submitted)
        }
        assertComposerText("printf history")
        composeRule.onNodeWithTag(TranscriptTags.HISTORY_NEWER).assertIsNotEnabled()
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
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithText("printf hello").assertExists()
        composeRule.onNodeWithText("hello").assertExists()
        composeRule.onNodeWithText("Succeeded · /srv/app · 35 ms · exit 0").assertExists()
    }

    @Test
    fun outputWebLinkRequiresConfirmationBeforeOpeningExactUrl() {
        val url = "https://example.com/report?q=threadline"
        var openedUrl: String? = null
        val turn = turn(
            status = CommandStatus.SUCCEEDED,
            output = url,
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                    onOpenUrl = { openedUrl = it },
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.output(turn.id.value)).performClick()
        composeRule.onNodeWithText("Open external link?").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(null, openedUrl)
        }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Open external link?").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(null, openedUrl)
        }

        composeRule.onNodeWithTag(TranscriptTags.output(turn.id.value)).performClick()
        composeRule.onNodeWithText("Open").performClick()
        composeRule.runOnIdle {
            assertEquals(url, openedUrl)
        }
    }

    @Test
    fun rejectedExternalOpenShowsAnErrorInsteadOfCrashing() {
        val turn = turn(
            status = CommandStatus.SUCCEEDED,
            output = "https://example.com",
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                    onOpenUrl = {
                        throw IllegalArgumentException("No activity can handle the URL")
                    },
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.output(turn.id.value)).performClick()
        composeRule.onNodeWithText("Open").performClick()

        composeRule.onNodeWithText("Could not open link").assertIsDisplayed()
        composeRule.onNodeWithText("No installed app accepted this web address.")
            .assertIsDisplayed()
    }

    @Test
    fun nonWebOutputSchemesDoNotBecomeLinks() {
        val turn = turn(
            status = CommandStatus.SUCCEEDED,
            output = "file:///tmp/report ssh://example.com javascript:alert(1)",
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LinkTestMarker),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun outputRemainsSelectableWhenItContainsAWebLink() {
        val turn = turn(
            status = CommandStatus.SUCCEEDED,
            output = "select this https://example.com safely",
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.output(turn.id.value))
            .performTouchInput {
                longClick(
                    position = percentOffset(x = 0.1f, y = 0.5f),
                    durationMillis = 1_000L,
                )
            }
        composeRule.onNodeWithText("Open external link?").assertDoesNotExist()
        composeRule.onAllNodes(isPopup(), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun runningTurnShowsLiveDuration() {
        val turn = turn(
            status = CommandStatus.RUNNING,
            submittedAtMillis = 100L,
            startedAtMillis = 110L,
        )

        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = runningShell(),
                    transcript = CommandTranscriptState(
                        turns = listOf(turn),
                        activeCommandId = turn.id,
                    ),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                    clockMillis = { 3_610L },
                )
            }
        }

        composeRule.onNodeWithText("Running · /tmp · 3.5 s").assertExists()
    }

    @Test
    fun stoppingTurnOffersDisconnectOnlyAfterInterruptGracePeriod() {
        var disconnected = false
        val turn = turn(
            status = CommandStatus.STOPPING,
            stopRequestedAtMillis = 1_000L,
        )

        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = runningShell(),
                    transcript = CommandTranscriptState(
                        turns = listOf(turn),
                        activeCommandId = turn.id,
                    ),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = { disconnected = true },
                    clockMillis = { 5_000L },
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Disconnect session")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(true, disconnected)
        }
    }

    @Test
    fun stoppingTurnReportsSentInterruptDuringGracePeriod() {
        val turn = turn(
            status = CommandStatus.STOPPING,
            stopRequestedAtMillis = 1_000L,
        )

        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = runningShell(),
                    transcript = CommandTranscriptState(
                        turns = listOf(turn),
                        activeCommandId = turn.id,
                    ),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                    clockMillis = { 3_999L },
                )
            }
        }

        composeRule.onNodeWithText("Interrupt sent").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Disconnect session")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun longOutputStartsCollapsedAndCanBeExpanded() {
        val turn = turn(
            status = CommandStatus.SUCCEEDED,
            output = "HEAD_ONLY\n" + "x".repeat(8_100) + "\nTAIL_ONLY",
        )

        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        assertTrue(
            composeRule.onAllNodesWithText("HEAD_ONLY", substring = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithText("TAIL_ONLY", substring = true).assertExists()
        composeRule.onNodeWithText("Show all")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Collapse").assertExists()
        assertTrue(
            composeRule.onAllNodesWithText(
                "Showing the latest 8000 characters",
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun transcriptFollowsNewTurnsWhileAlreadyFollowingOutput() {
        var listState: LazyListState? = null
        val transcript = mutableStateOf(
            CommandTranscriptState(
                turns = (0 until 10).map { index ->
                    turn(
                        id = "command-$index",
                        command = "printf command-$index",
                        status = CommandStatus.SUCCEEDED,
                    )
                },
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                val rememberedListState = rememberLazyListState()
                listState = rememberedListState
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = transcript.value,
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onStop = {},
                    onDisconnect = {},
                    listState = rememberedListState,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                "transcript-tail",
                requireNotNull(listState)
                    .layoutInfo
                    .visibleItemsInfo
                    .lastOrNull()
                    ?.key,
            )
        }
        composeRule.runOnIdle {
            transcript.value = CommandTranscriptState(
                turns = transcript.value.turns + turn(
                    id = "command-10",
                    command = "printf command-10",
                    status = CommandStatus.SUCCEEDED,
                ),
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                "transcript-tail",
                requireNotNull(listState)
                    .layoutInfo
                    .visibleItemsInfo
                    .lastOrNull()
                    ?.key,
            )
        }
    }

    private fun runningShell() = StructuredShellState.Running(
        activeCommand = ActiveCommand(
            id = CommandId("command-42"),
            command = "test command",
            directoryAtStart = "/tmp",
            phase = LifecyclePhase.OUTPUT_STARTED,
        ),
        lastCommand = null,
    )

    private fun assertComposerText(expected: String) {
        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(expected),
                ),
            )
    }

    private fun turn(
        id: String = "command-42",
        command: String = "test command",
        status: CommandStatus,
        submittedAtMillis: Long = 100L,
        startedAtMillis: Long? = 110L,
        stopRequestedAtMillis: Long? = null,
        output: String = "",
    ) = CommandTurn(
        id = CommandId(id),
        command = command,
        directoryAtStart = "/tmp",
        submittedAtMillis = submittedAtMillis,
        startedAtMillis = startedAtMillis,
        completedAtMillis = if (status == CommandStatus.SUCCEEDED) 145L else null,
        status = status,
        exitStatus = if (status == CommandStatus.SUCCEEDED) 0 else null,
        currentDirectory = if (status == CommandStatus.SUCCEEDED) "/tmp" else null,
        output = CommandOutput(
            plainText = output,
            byteCount = output.encodeToByteArray().size.toLong(),
        ),
        stopRequestedAtMillis = stopRequestedAtMillis,
    )
}
