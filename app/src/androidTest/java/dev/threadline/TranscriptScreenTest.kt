package dev.threadline

import android.content.ComponentName
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.threadline.core.shell.ActiveCommand
import dev.threadline.core.shell.CommandExecutionMode
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.CompletedCommand
import dev.threadline.core.shell.LifecyclePhase
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.terminal.TerminalKey
import dev.threadline.core.terminal.TerminalModifiers
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.CommandTurn
import dev.threadline.core.transcript.InteractiveTerminalHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TranscriptScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
    fun strictCommandCanRunIsolatedWithoutUsingPersistentSubmission() {
        var persistentSubmission: String? = null
        var isolatedSubmission: String? = null
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(),
                    onSubmit = { command ->
                        persistentSubmission = command
                        CommandSubmissionResult.Accepted(CommandId("persistent"))
                    },
                    onSubmitIsolated = { command ->
                        isolatedSubmission = command
                        CommandSubmissionResult.Accepted(CommandId("isolated"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }
        val command = "set -euo pipefail\nfalse"

        composeRule.onNodeWithTag(TranscriptTags.COMPOSER).performTextInput(command)
        composeRule.onNodeWithText(
            "Persistent Send can close the shell after a failure. " +
                "Run isolated unless those changes need to persist.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(TranscriptTags.RUN_ISOLATED)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(null, persistentSubmission)
            assertEquals(command, isolatedSubmission)
        }
        assertComposerText("")
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
    fun runningCommandAllowsDraftingAcrossTerminalAndStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val structuredShell = mutableStateOf<StructuredShellState>(runningShell())
        var submitted: String? = null
        restorationTester.setContent {
            MaterialTheme {
                ConnectedSessionScreen(
                    displayName = "Draft test session",
                    structuredShell = structuredShell.value,
                    transcript = CommandTranscriptState(),
                    onSubmit = { command ->
                        submitted = command
                        CommandSubmissionResult.Accepted(CommandId("next-command"))
                    },
                    onControlC = {},
                    onDisconnect = {},
                    rawTerminal = { modifier ->
                        Text("Raw draft test surface", modifier = modifier)
                    },
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .assertIsEnabled()
            .performTextInput("printf next")
        composeRule.onNodeWithTag(TranscriptTags.SEND).assertIsNotEnabled()

        composeRule.onNodeWithTag(TranscriptTags.MODE_SWITCH).performClick()
        composeRule.onNodeWithText("Raw draft test surface").assertIsDisplayed()
        composeRule.onNodeWithTag(TranscriptTags.MODE_SWITCH).performClick()
        assertComposerText("printf next")

        restorationTester.emulateSavedInstanceStateRestore()
        assertComposerText("printf next")
        composeRule.onNodeWithTag(TranscriptTags.SEND).assertIsNotEnabled()

        composeRule.runOnIdle {
            structuredShell.value = StructuredShellState.Ready(
                currentDirectory = "/tmp",
                lastCommand = CompletedCommand(
                    id = CommandId("command-42"),
                    command = "test command",
                    directoryAtStart = "/tmp",
                    currentDirectory = "/tmp",
                    exitStatus = 1,
                ),
            )
        }
        composeRule.onNodeWithTag(TranscriptTags.SEND)
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("printf next", submitted)
        }
        assertComposerText("")
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
    fun isolatedCardIsLabeledAndRerunsInIsolatedMode() {
        var persistentSubmission: String? = null
        var isolatedSubmission: String? = null
        val turn = turn(
            command = "printf isolated",
            executionMode = CommandExecutionMode.ISOLATED,
            status = CommandStatus.SUCCEEDED,
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = { command ->
                        persistentSubmission = command
                        CommandSubmissionResult.Accepted(CommandId("persistent-rerun"))
                    },
                    onSubmitIsolated = { command ->
                        isolatedSubmission = command
                        CommandSubmissionResult.Accepted(CommandId("isolated-rerun"))
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithText("Isolated · Succeeded · /tmp · 35 ms · exit 0")
            .assertExists()
        composeRule.onNodeWithText("Rerun").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(null, persistentSubmission)
            assertEquals(turn.command, isolatedSubmission)
        }
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
    fun interactiveSuggestionOpensRawTerminalAndCanReturnToTranscript() {
        val turn = turn(
            status = CommandStatus.RUNNING,
            interactiveHint = InteractiveTerminalHint.ALTERNATE_SCREEN,
        )
        composeRule.setContent {
            MaterialTheme {
                ConnectedSessionScreen(
                    displayName = "Test session",
                    structuredShell = runningShell(),
                    transcript = CommandTranscriptState(
                        turns = listOf(turn),
                        activeCommandId = turn.id,
                    ),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onControlC = {},
                    onDisconnect = {},
                    rawTerminal = { modifier ->
                        Text("Raw terminal test surface", modifier = modifier)
                    },
                )
            }
        }

        composeRule.onNodeWithText("This command may need interactive input.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TranscriptTags.INTERACTIVE_OPEN).performClick()
        composeRule.onNodeWithText("Raw terminal test surface").assertIsDisplayed()
        composeRule.onNodeWithText("This command may need interactive input.")
            .assertDoesNotExist()

        composeRule.onNodeWithText("Transcript").performClick()
        composeRule.onNodeWithText("This command may need interactive input.")
            .assertIsDisplayed()
    }

    @Test
    fun connectedSessionHomeActionDoesNotDisconnect() {
        var homeCount = 0
        var disconnectCount = 0

        composeRule.setContent {
            MaterialTheme {
                ConnectedSessionScreen(
                    displayName = "Test session",
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onControlC = {},
                    onDisconnect = { disconnectCount += 1 },
                    onOpenHome = { homeCount += 1 },
                    rawTerminal = { modifier -> Text("Terminal", modifier = modifier) },
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.HOME).performClick()
        composeRule.runOnIdle {
            assertEquals(1, homeCount)
            assertEquals(0, disconnectCount)
        }
    }

    @Test
    fun boundedLargeOutputCanExpandAndSwitchViewsWithinFiveSeconds() {
        val output = buildString {
            while (length < 128 * 1024) {
                append("0123456789 lambda π 日本語\n")
            }
        }.takeLast(128 * 1024)
        val turn = CommandTurn(
            id = CommandId("large-output"),
            command = "generate bounded output",
            directoryAtStart = "/tmp",
            submittedAtMillis = 100L,
            startedAtMillis = 110L,
            completedAtMillis = 145L,
            status = CommandStatus.SUCCEEDED,
            exitStatus = 0,
            currentDirectory = "/tmp",
            output = CommandOutput(
                plainText = output,
                truncated = true,
                byteCount = 2_000_000L,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                ConnectedSessionScreen(
                    displayName = "Large-output session",
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Accepted(CommandId("unused"))
                    },
                    onControlC = {},
                    onDisconnect = {},
                    rawTerminal = { modifier ->
                        Text("Raw performance surface", modifier = modifier)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Showing the latest 8000 characters").assertExists()
        val expandStarted = SystemClock.elapsedRealtime()
        composeRule.onNodeWithText("Show all").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Collapse").assertExists()
        assertTrue(
            "Expanding the bounded output exceeded five seconds",
            SystemClock.elapsedRealtime() - expandStarted <= UI_RESPONSE_LIMIT_MILLIS,
        )

        val terminalStarted = SystemClock.elapsedRealtime()
        composeRule.onNodeWithTag(TranscriptTags.MODE_SWITCH).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Raw performance surface").assertIsDisplayed()
        assertTrue(
            "Opening the raw terminal exceeded five seconds",
            SystemClock.elapsedRealtime() - terminalStarted <= UI_RESPONSE_LIMIT_MILLIS,
        )

        val transcriptStarted = SystemClock.elapsedRealtime()
        composeRule.onNodeWithTag(TranscriptTags.MODE_SWITCH).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TranscriptTags.TRANSCRIPT).assertExists()
        assertTrue(
            "Returning to the expanded transcript exceeded five seconds",
            SystemClock.elapsedRealtime() - transcriptStarted <= UI_RESPONSE_LIMIT_MILLIS,
        )
    }

    @Test
    fun terminalExtraKeyRowExposesOneShotModifiersAndTerminalKeys() {
        val modifiers = mutableStateOf(TerminalModifiers())
        var sentKey: TerminalKey? = null
        composeRule.setContent {
            MaterialTheme {
                TerminalExtraKeyRow(
                    modifiers = modifiers.value,
                    onToggleControl = {
                        modifiers.value = modifiers.value.copy(
                            control = !modifiers.value.control,
                        )
                    },
                    onToggleAlt = {
                        modifiers.value = modifiers.value.copy(
                            alt = !modifiers.value.alt,
                        )
                    },
                    onKey = {
                        sentKey = it
                        modifiers.value = TerminalModifiers()
                    },
                )
            }
        }

        composeRule.onNodeWithTag(TranscriptTags.TERMINAL_CONTROL)
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag(TranscriptTags.TERMINAL_ALT)
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()

        TerminalKey.entries.forEach { key ->
            composeRule.onNodeWithTag(TranscriptTags.terminalKey(key)).assertExists()
        }
        composeRule.onNodeWithTag(TranscriptTags.terminalKey(TerminalKey.ARROW_UP))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Arrow up"),
                ),
            )
            .performClick()
        composeRule.runOnIdle {
            assertEquals(TerminalKey.ARROW_UP, sentKey)
        }
        composeRule.onNodeWithTag(TranscriptTags.TERMINAL_CONTROL).assertIsNotSelected()
        composeRule.onNodeWithTag(TranscriptTags.TERMINAL_ALT).assertIsNotSelected()
    }

    @Test
    fun commandHeadingAndSubmissionFailureExposeAccessibilitySemantics() {
        val turn = turn(
            command = "printf accessible",
            status = CommandStatus.SUCCEEDED,
        )
        composeRule.setContent {
            MaterialTheme {
                TranscriptSurface(
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = CommandTranscriptState(turns = listOf(turn)),
                    onSubmit = {
                        CommandSubmissionResult.Rejected(
                            CommandSubmissionRejection.INPUT_BACKPRESSURE,
                        )
                    },
                    onStop = {},
                    onDisconnect = {},
                )
            }
        }

        composeRule.onNodeWithText("printf accessible", useUnmergedTree = true).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        composeRule.onNodeWithTag(TranscriptTags.COMPOSER).performTextInput("date")
        composeRule.onNodeWithTag(TranscriptTags.SEND).performClick()
        composeRule.onNodeWithTag(TranscriptTags.SUBMISSION_ERROR).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            ),
        )
        composeRule.onNodeWithText("The session input queue is full. Try again.")
            .assertIsDisplayed()
    }

    @Test
    fun connectedSessionActionsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    ConnectedSessionScreen(
                        displayName = "Accessibility test session",
                        structuredShell = StructuredShellState.Ready("/tmp"),
                        transcript = CommandTranscriptState(),
                        onSubmit = {
                            CommandSubmissionResult.Accepted(CommandId("unused"))
                        },
                        onControlC = {},
                        onDisconnect = {},
                        onOpenDiagnostics = {},
                        rawTerminal = { modifier -> Text("Terminal", modifier = modifier) },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Accessibility test session", useUnmergedTree = true).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        composeRule.onNodeWithText("Terminal").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Home").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Diagnostics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Disconnect").performScrollTo().assertIsDisplayed()
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

    @Suppress("DEPRECATION")
    @Test
    fun mainActivityRequestsResizeForIme() {
        val activityInfo = composeRule.activity.packageManager.getActivityInfo(
            ComponentName(composeRule.activity, MainActivity::class.java),
            0,
        )

        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activityInfo.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }

    @Test
    fun transcriptKeepsLatestTurnVisibleWhenImeInsetsArrive() {
        assertSubmittedTurnVisibleWithIme(
            initialTurns = (0 until 6).map { index ->
                turn(
                    id = "command-$index",
                    command = "printf command-$index",
                    status = CommandStatus.SUCCEEDED,
                    output = "result-$index",
                )
            },
        )
    }

    @Test
    fun transcriptKeepsFirstTurnVisibleWhenImeInsetsArrive() {
        assertSubmittedTurnVisibleWithIme(initialTurns = emptyList())
    }

    private fun assertSubmittedTurnVisibleWithIme(initialTurns: List<CommandTurn>) {
        composeRule.runOnIdle {
            composeRule.activity.enableEdgeToEdge()
        }
        lateinit var composeView: View
        val latestTurnId = "ime-command"
        val transcript = mutableStateOf(CommandTranscriptState(turns = initialTurns))
        composeRule.setContent {
            MaterialTheme {
                ConnectedSessionScreen(
                    displayName = "Keyboard viewport test",
                    structuredShell = StructuredShellState.Ready("/tmp"),
                    transcript = transcript.value,
                    onSubmit = { command ->
                        transcript.value = CommandTranscriptState(
                            turns = transcript.value.turns + turn(
                                id = latestTurnId,
                                command = command,
                                status = CommandStatus.SUCCEEDED,
                                output = "latest result",
                            ),
                        )
                        CommandSubmissionResult.Accepted(CommandId(latestTurnId))
                    },
                    onControlC = {},
                    onDisconnect = {},
                )
            }
        }
        initialTurns.lastOrNull()?.let { lastTurn ->
            composeRule.onNodeWithTag(TranscriptTags.output(lastTurn.id.value))
                .assertIsDisplayed()
        }
        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .performClick()
            .assertIsFocused()
        composeRule.runOnIdle {
            composeView = composeRule.activity
                .findViewById<ViewGroup>(android.R.id.content)
                .getChildAt(0)
            WindowInsetsControllerCompat(
                composeRule.activity.window,
                composeView,
            ).show(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            ViewCompat.getRootWindowInsets(composeView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        val composerBottom = composeRule
            .onNodeWithTag(TranscriptTags.COMPOSER)
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val visibleBottom = ViewCompat.getRootWindowInsets(composeView)
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.let { composeView.height - it.bottom }
        assertTrue(
            "Composer bottom $composerBottom exceeded IME top $visibleBottom",
            visibleBottom != null && composerBottom <= visibleBottom,
        )
        initialTurns.lastOrNull()?.let { lastTurn ->
            composeRule.onNodeWithTag(TranscriptTags.output(lastTurn.id.value))
                .assertIsDisplayed()
        }
        composeRule.onNodeWithTag(TranscriptTags.COMPOSER)
            .performTextInput("printf latest")
        composeRule.onNodeWithTag(TranscriptTags.SEND).performClick()

        composeRule.onNodeWithTag(TranscriptTags.output(latestTurnId))
            .assertIsDisplayed()
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
        executionMode: CommandExecutionMode = CommandExecutionMode.PERSISTENT,
        status: CommandStatus,
        submittedAtMillis: Long = 100L,
        startedAtMillis: Long? = 110L,
        stopRequestedAtMillis: Long? = null,
        output: String = "",
        interactiveHint: InteractiveTerminalHint? = null,
    ) = CommandTurn(
        id = CommandId(id),
        command = command,
        executionMode = executionMode,
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
            interactiveHint = interactiveHint,
        ),
        stopRequestedAtMillis = stopRequestedAtMillis,
    )

    private companion object {
        const val UI_RESPONSE_LIMIT_MILLIS = 5_000L
    }
}
