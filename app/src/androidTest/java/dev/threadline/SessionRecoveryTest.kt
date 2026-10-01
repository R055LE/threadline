package dev.threadline

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.ConnectionTarget
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.model.SessionError
import dev.threadline.core.transcript.CommandTranscript
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.ShellLifecycleEvent
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.shell.StructuredShellUnavailableReason
import dev.threadline.core.shell.CommandSubmissionResult
import dev.threadline.core.shell.CommandSubmissionRejection
import dev.threadline.data.identity.IdentityAuthenticationMethod
import dev.threadline.data.identity.SshIdentity
import dev.threadline.data.key.ImportedPrivateKeyMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SessionRecoveryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shellEndKeepsOutputReadOnlyAndRequiresAnExplicitFreshShellAtLargeFontScale() {
        val transcript = CommandTranscript()
        transcript.commandSubmitted(CommandId("ended-command"), "exit 7", directoryAtStart = "/tmp")
        transcript.lifecycle(ShellLifecycleEvent.CommandOutputStarted(CommandId("ended-command")))
        transcript.consumeOutput("partial output\n".encodeToByteArray())
        transcript.sessionDisconnected()
        val snapshot = transcript.state.value
        var reconnects = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    EndedSessionScreen(
                        target = target(),
                        error = SessionError.ShellEnded(7),
                        transcript = snapshot,
                        onReconnect = { reconnects += 1 },
                        onOpenHome = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Shell ended").assertExists()
        compose.onNodeWithText("The remote shell ended with exit status 7.").assertExists()
        compose.onNodeWithText(FreshShellNotice).assertExists()
        compose.onNodeWithTag(TranscriptTags.output("ended-command")).performScrollTo()
        compose.onNodeWithText("partial output\n").assertIsDisplayed()
        compose.onNodeWithTag(TranscriptTags.COMPOSER).assertDoesNotExist()
        compose.onNodeWithText("Rerun").assertDoesNotExist()
        compose.onNodeWithTag(TranscriptTags.cardActions("ended-command")).performScrollTo().performClick()
        compose.onNodeWithText("Edit command").assertDoesNotExist()
        compose.onNodeWithText("Copy output").performClick()
        compose.runOnIdle { assertEquals(0, reconnects) }
        compose.onNodeWithTag(TranscriptTags.RECONNECT).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reconnects) }
    }

    @Test
    fun connectionLossShowsNetworkRecoveryAndKeepsHomeAvailable() {
        var homeOpened = false
        compose.setContent {
            MaterialTheme {
                EndedSessionScreen(
                    target = target(),
                    error = SessionError.ConnectionLost,
                    transcript = CommandTranscriptState(),
                    onReconnect = {},
                    onOpenHome = { homeOpened = true },
                )
            }
        }
        compose.onNodeWithText("Connection lost").assertExists()
        compose.onNodeWithText("Shell ended").assertDoesNotExist()
        compose.onNodeWithText("Check the device network, VPN, and server status before reconnecting.")
            .assertExists()
        compose.onNodeWithText("Home").performClick()
        compose.runOnIdle { assertEquals(true, homeOpened) }
    }

    @Test
    fun integrationDowngradeKeepsRawTerminalAccessWithoutAnEndedSessionPrompt() {
        compose.setContent {
            MaterialTheme {
                ConnectedSessionScreen(
                    displayName = "Downgrade fixture",
                    structuredShell = StructuredShellState.Unavailable(
                        StructuredShellUnavailableReason.BOOTSTRAP_TIMED_OUT,
                    ),
                    transcript = CommandTranscriptState(),
                    onSubmit = { CommandSubmissionResult.Rejected(CommandSubmissionRejection.NOT_READY) },
                    onControlC = {},
                    onDisconnect = {},
                    rawTerminal = { modifier, _ ->
                        androidx.compose.material3.Text("Live raw terminal", modifier)
                    },
                )
            }
        }
        compose.onNodeWithText("Transcript unavailable · terminal available").assertExists()
        compose.onNodeWithText("Live raw terminal").assertIsDisplayed()
        compose.onNodeWithTag(TranscriptTags.ENDED_SESSION).assertDoesNotExist()
        compose.onNodeWithTag(TranscriptTags.RECONNECT).assertDoesNotExist()
    }

    @Test
    fun freshKeyConnectionKeepsTheOriginalEndpointUsernameAndKeyChoice() {
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())
        var loads = 0
        var prepared: ConnectionRequest? = null
        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    reconnectTarget = target(usesPrivateKey = true),
                    importedPrivateKeys = listOf(key("original-key"), key("replacement-key")),
                    sshIdentities = listOf(identity(
                        method = IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY,
                        username = "edited-user",
                        privateKeyId = "replacement-key",
                    )),
                    onLoadPrivateKey = { id, _ ->
                        assertEquals("original-key", id)
                        loads += 1
                        SessionCredential.PrivateKey.from(byteArrayOf(1, 2), null)
                    },
                    onPrepared = { prepared = it; true },
                )
            }
        }
        compose.onNodeWithText("Complete sign-in").assertExists()
        compose.onNodeWithText("chosen-user@fixture.test:2222").assertExists()
        compose.onNodeWithTag(ConnectionFormTags.SAVED_KEY_PREFIX + "original-key").assertIsSelected()
        compose.runOnIdle { assertEquals(0, loads); assertNull(prepared) }
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val request = requireNotNull(prepared)
            assertEquals(1, loads)
            assertEquals(target().profile, request.profile)
            assertEquals("chosen-identity", request.identityId)
            assertEquals("original-key", request.importedPrivateKeyId)
            assertEquals(true, request.ephemeral)
            request.credential.clear()
        }
    }

    @Test
    fun freshConnectionKeepsItsKeyChoiceThroughNotificationPermissionRecovery() {
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())
        val permission = mutableStateOf(SessionNotificationPermissionState.DENIED)
        var prepared: ConnectionRequest? = null
        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    reconnectTarget = target(usesPrivateKey = true),
                    notificationPermissionState = permission.value,
                    importedPrivateKeys = listOf(key("original-key")),
                    onLoadPrivateKey = { id, _ ->
                        assertEquals("original-key", id)
                        SessionCredential.PrivateKey.from(byteArrayOf(1, 2), null)
                    },
                    onPrepared = { prepared = it; true },
                )
            }
        }
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).assertDoesNotExist()
        compose.runOnIdle { permission.value = SessionNotificationPermissionState.GRANTED }
        compose.onNodeWithTag(ConnectionFormTags.SAVED_KEY_PREFIX + "original-key").assertIsSelected()
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("original-key", requireNotNull(prepared).importedPrivateKeyId)
            requireNotNull(prepared).credential.clear()
        }
    }

    @Test
    fun freshSavedPasswordConnectionRequiresANewUnlock() {
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())
        var unlocks = 0
        var prepared: ConnectionRequest? = null
        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    reconnectTarget = target(),
                    sshIdentities = listOf(identity(IdentityAuthenticationMethod.PASSWORD)),
                    savedPasswordSupported = true,
                    onLoadSavedSshPassword = { id ->
                        assertEquals("chosen-identity", id)
                        unlocks += 1
                        SessionCredential.Password.from("fresh-password".toCharArray())
                    },
                    onPrepared = { prepared = it; true },
                )
            }
        }
        compose.runOnIdle { assertEquals(0, unlocks); assertNull(prepared) }
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, unlocks)
            assertEquals(target().profile, requireNotNull(prepared).profile)
            requireNotNull(prepared).credential.clear()
        }
    }

    @Test
    fun freshConnectionClearsAnEarlierPasswordAndRequiresNewEntry() {
        val draft = mutableStateOf(ConnectionFormDraft.fixtureDefaults())
        val recovery = mutableStateOf<ConnectionTarget?>(null)
        var prepared: ConnectionRequest? = null
        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    reconnectTarget = recovery.value,
                    onPrepared = { prepared = it; true },
                )
            }
        }
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD).performScrollTo().performTextInput("old-password")
        compose.runOnIdle { recovery.value = target() }
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.onNodeWithText("Enter the password.").assertExists()
        compose.runOnIdle { assertNull(prepared) }
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD).performTextInput("fresh-password")
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val password = requireNotNull(prepared).credential as SessionCredential.Password
            assertEquals("fresh-password", password.characters.concatToString())
            password.clear()
        }
    }

    private fun target(usesPrivateKey: Boolean = false) = ConnectionTarget(
        profile = HostProfile("Recovery fixture", HostEndpoint("fixture.test", 2222), "chosen-user"),
        usesPrivateKey = usesPrivateKey,
        ephemeral = true,
        identityId = "chosen-identity",
        importedPrivateKeyId = "original-key".takeIf { usesPrivateKey },
        privateKeyUri = null,
    )

    private fun identity(
        method: IdentityAuthenticationMethod,
        username: String = "chosen-user",
        privateKeyId: String? = null,
    ) = SshIdentity(
        id = "chosen-identity", label = "Chosen identity", username = username,
        authenticationMethod = method, importedPrivateKeyId = privateKeyId,
        createdAtMillis = 1, updatedAtMillis = 1,
        hasSavedPassword = method == IdentityAuthenticationMethod.PASSWORD,
    )

    private fun key(id: String) = ImportedPrivateKeyMetadata(
        id = id, displayName = id, format = "OpenSSH", keyType = "ssh-ed25519",
        publicKeyFingerprint = "fixture fingerprint", createdAtMillis = 1,
    )
}
