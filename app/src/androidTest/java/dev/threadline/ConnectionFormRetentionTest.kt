package dev.threadline

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.core.model.SessionCredential
import dev.threadline.core.shell.CommandId
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTurn
import dev.threadline.data.host.KnownHostMetadata
import dev.threadline.data.key.ImportedPrivateKeyMetadata
import dev.threadline.data.profile.SavedHostProfile
import dev.threadline.data.transcript.SavedTranscriptSession
import dev.threadline.data.transcript.SavedTranscriptSessionSummary
import dev.threadline.data.transcript.SavedTranscriptTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionFormRetentionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectionFormReopensHelpAndExplainsProfileCredentialBoundary() {
        var helpOpenCount = 0
        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = ConnectionFormDraft.emptyDefaults(),
                    onDraftChange = {},
                    sessionError = null,
                    onOpenIntroduction = { helpOpenCount += 1 },
                    onPrepared = { true },
                )
            }
        }

        compose.onNodeWithText("Connect to a server").assertExists()
        compose.onNodeWithText("Passwords and private-key passphrases are never saved", substring = true)
            .performScrollTo()
            .assertExists()
        compose.onNodeWithTag(ConnectionFormTags.HELP).performClick()
        compose.runOnIdle { assertEquals(1, helpOpenCount) }
    }

    @Test
    fun blankPasswordUsesProductionSafeValidationCopy() {
        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = ConnectionFormDraft.fixtureDefaults(),
                    onDraftChange = {},
                    sessionError = null,
                    onPrepared = { true },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.CONNECT)
            .performScrollTo()
            .performClick()
        compose.waitUntil {
            compose.onAllNodesWithText("Enter the password.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        compose.onNodeWithText("Enter the password.").assertExists()
        compose.onAllNodesWithText("Enter the fixture password.").assertCountEquals(0)
    }

    @Test
    fun failedConnectionRetainsNonSecretFieldsAndClearsPassword() {
        val formVisible = mutableStateOf(true)

        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }

            if (formVisible.value) {
                MaterialTheme {
                    HostForm(
                        draft = draft,
                        onDraftChange = { draft = it },
                        sessionError = null,
                        onPrepared = { true },
                    )
                }
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.DISPLAY_NAME)
            .performTextReplacement("Lab server")
        compose.onNodeWithTag(ConnectionFormTags.HOSTNAME)
            .performTextReplacement("192.0.2.10")
        compose.onNodeWithTag(ConnectionFormTags.PORT)
            .performTextReplacement("2200")
        compose.onNodeWithTag(ConnectionFormTags.USERNAME)
            .performTextReplacement("operator")
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .performTextReplacement("session-only")

        compose.runOnIdle { formVisible.value = false }
        compose.runOnIdle { formVisible.value = true }

        compose.onNodeWithTag(ConnectionFormTags.DISPLAY_NAME)
            .assertEditableTextEquals("Lab server")
        compose.onNodeWithTag(ConnectionFormTags.HOSTNAME)
            .assertEditableTextEquals("192.0.2.10")
        compose.onNodeWithTag(ConnectionFormTags.PORT)
            .assertEditableTextEquals("2200")
        compose.onNodeWithTag(ConnectionFormTags.USERNAME)
            .assertEditableTextEquals("operator")
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD_AUTH).assertIsSelected()
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .assertEditableTextEquals("")
    }

    @Test
    fun failedConnectionRetainsPrivateKeyChoiceAndClearsPassphrase() {
        val formVisible = mutableStateOf(true)

        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }

            if (formVisible.value) {
                MaterialTheme {
                    HostForm(
                        draft = draft,
                        onDraftChange = { draft = it },
                        sessionError = null,
                        onPrepared = { true },
                    )
                }
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.PRIVATE_KEY_AUTH).performClick()
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .performTextReplacement("session-only")

        compose.runOnIdle { formVisible.value = false }
        compose.runOnIdle { formVisible.value = true }

        compose.onNodeWithTag(ConnectionFormTags.PRIVATE_KEY_AUTH).assertIsSelected()
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .assertEditableTextEquals("")
    }

    @Test
    fun ephemeralChoiceIsExplicitAndReachesPreparedRequest() {
        var preparedEphemeral: Boolean? = null

        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }
            MaterialTheme {
                HostForm(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sessionError = null,
                    onPrepared = { request ->
                        preparedEphemeral = request.ephemeral
                        true
                    },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.EPHEMERAL)
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.Off,
                ),
            )
            .performClick()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                ),
            )
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .performTextReplacement("session-only")
        compose.onNodeWithTag(ConnectionFormTags.CONNECT)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertEquals(true, preparedEphemeral)
    }

    @Test
    fun savedTranscriptCanBeOpenedAndOnlyExplicitlyDeletedOrCleared() {
        val summaries = mutableStateOf(
            listOf(
                transcriptSummary("session-1", "First"),
                transcriptSummary("session-2", "Second"),
            ),
        )
        var deletedId: String? = null
        var clearCount = 0

        compose.setContent {
            MaterialTheme {
                TranscriptHistorySection(
                    sessions = summaries.value,
                    saveFailed = false,
                    onLoad = { id -> transcriptSession(id) },
                    onDelete = { id ->
                        deletedId = id
                        summaries.value = summaries.value.filterNot { it.id == id }
                    },
                    onClearAll = {
                        clearCount += 1
                        summaries.value = emptyList()
                    },
                )
            }
        }

        compose.onNodeWithTag(TranscriptHistoryTags.OPEN).performClick()
        compose.onNodeWithTag(TranscriptHistoryTags.SESSION_PREFIX + "session-1")
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText("printf saved").assertExists()
        compose.onNodeWithTag(TranscriptHistoryTags.OUTPUT_PREFIX + "command-session-1")
            .assertExists()
        compose.onNodeWithText("Back").performClick()

        compose.onNodeWithTag(TranscriptHistoryTags.DELETE_PREFIX + "session-1")
            .performClick()
        assertNull(deletedId)
        compose.onNodeWithText("Delete saved transcript?").assertExists()
        compose.onNodeWithTag(TranscriptHistoryTags.CONFIRM_DELETE).performClick()
        compose.waitForIdle()
        assertEquals("session-1", deletedId)

        compose.onNodeWithTag(TranscriptHistoryTags.OPEN).performClick()
        compose.onNodeWithTag(TranscriptHistoryTags.CLEAR_ALL).performClick()
        assertEquals(0, clearCount)
        compose.onNodeWithText("Clear all transcript history?").assertExists()
        compose.onNodeWithTag(TranscriptHistoryTags.CONFIRM_CLEAR_ALL).performClick()
        compose.waitForIdle()
        assertEquals(1, clearCount)
        compose.onAllNodesWithTag(TranscriptHistoryTags.OPEN).assertCountEquals(0)
    }

    @Test
    fun savedKeySelectionLoadsCredentialAndWipesTemporaryPassphrase() {
        val savedKey = ImportedPrivateKeyMetadata(
            id = "saved-id",
            displayName = "Fixture key",
            format = "OpenSSH",
            keyType = "ssh-ed25519",
            publicKeyFingerprint = "SHA256:fixture",
            createdAtMillis = 1,
        )
        var receivedPassphrase: CharArray? = null
        var preparedCredential: SessionCredential.PrivateKey? = null

        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }
            MaterialTheme {
                HostForm(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sessionError = null,
                    importedPrivateKeys = listOf(savedKey),
                    onLoadPrivateKey = { _, passphrase ->
                        receivedPassphrase = passphrase
                        assertEquals("session-only", passphrase?.concatToString())
                        SessionCredential.PrivateKey.from(byteArrayOf(1, 2, 3), passphrase)
                    },
                    onPrepared = { request ->
                        preparedCredential = request.credential as SessionCredential.PrivateKey
                        true
                    },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.PRIVATE_KEY_AUTH).performClick()
        compose.onNodeWithTag(ConnectionFormTags.SAVED_KEY_PREFIX + savedKey.id).performClick()
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .performTextReplacement("session-only")
        compose.onNodeWithTag(ConnectionFormTags.CONNECT)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertNotNull(preparedCredential)
        assertTrue(requireNotNull(receivedPassphrase).all { it == '\u0000' })
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .assertEditableTextEquals("")
        preparedCredential?.clear()
    }

    @Test
    fun savedKeyRenameAndDeleteRequireExplicitConfirmation() {
        val savedKey = ImportedPrivateKeyMetadata(
            id = "managed-id",
            displayName = "Old name",
            format = "OpenSSH",
            keyType = "ssh-ed25519",
            publicKeyFingerprint = "SHA256:managed-fixture",
            createdAtMillis = 1,
        )
        var renamed: Pair<String, String>? = null
        var deletedId: String? = null

        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }
            MaterialTheme {
                HostForm(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sessionError = null,
                    importedPrivateKeys = listOf(savedKey),
                    onRenamePrivateKey = { id, name -> renamed = id to name },
                    onDeletePrivateKey = { id -> deletedId = id },
                    onPrepared = { true },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.PRIVATE_KEY_AUTH).performClick()
        compose.onNodeWithTag(ConnectionFormTags.RENAME_KEY_PREFIX + savedKey.id).performClick()
        compose.onNodeWithTag(ConnectionFormTags.RENAME_KEY_NAME)
            .performTextReplacement("New name")
        compose.onNodeWithTag(ConnectionFormTags.CONFIRM_RENAME_KEY).performClick()
        compose.waitForIdle()
        assertEquals(savedKey.id to "New name", renamed)

        compose.onNodeWithTag(ConnectionFormTags.SAVED_KEY_PREFIX + savedKey.id).performClick()
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .performTextReplacement("delete-me")
        compose.onNodeWithTag(ConnectionFormTags.DELETE_KEY_PREFIX + savedKey.id).performClick()
        assertNull(deletedId)
        compose.onNodeWithText("Delete saved key?").assertExists()
        compose.onAllNodesWithText("ssh-ed25519 · SHA256:managed-fixture")
            .assertCountEquals(2)
        compose.onNodeWithTag(ConnectionFormTags.CONFIRM_DELETE_KEY).performClick()
        compose.waitForIdle()

        assertEquals(savedKey.id, deletedId)
        compose.onNodeWithTag(ConnectionFormTags.SAVED_KEY_PREFIX + savedKey.id)
            .assertIsNotSelected()
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .assertEditableTextEquals("")
    }

    @Test
    fun hostProfileSaveSelectUpdateAndDeleteKeepCredentialsSessionOnly() {
        val original = SavedHostProfile(
            id = "profile-id",
            displayName = "Lab",
            hostname = "lab.example",
            port = 2200,
            username = "operator",
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        val profiles = mutableStateOf(emptyList<SavedHostProfile>())
        val selectedId = mutableStateOf<String?>(null)
        val draftState = mutableStateOf(ConnectionFormDraft.fixtureDefaults())
        var savedProfile: HostProfile? = null
        var updatedProfile: Pair<String, HostProfile>? = null
        var deletedId: String? = null

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draftState.value,
                    onDraftChange = { draftState.value = it },
                    sessionError = null,
                    hostProfiles = profiles.value,
                    selectedHostProfileId = selectedId.value,
                    onSelectedHostProfileChange = { selectedId.value = it },
                    onSaveHostProfile = { profile ->
                        savedProfile = profile
                        profiles.value = listOf(original)
                        original
                    },
                    onUpdateHostProfile = { id, profile ->
                        updatedProfile = id to profile
                    },
                    onDeleteHostProfile = { id -> deletedId = id },
                    onPrepared = { true },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SAVE_PROFILE).performClick()
        compose.waitForIdle()
        assertEquals("Local fixture", savedProfile?.displayName)
        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + original.id)
            .assertIsSelected()

        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .performTextReplacement("session-only")
        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + original.id).performClick()
        compose.onNodeWithTag(ConnectionFormTags.DISPLAY_NAME)
            .assertEditableTextEquals("Lab")
        compose.onNodeWithTag(ConnectionFormTags.HOSTNAME)
            .assertEditableTextEquals("lab.example")
        compose.onNodeWithTag(ConnectionFormTags.PORT)
            .assertEditableTextEquals("2200")
        compose.onNodeWithTag(ConnectionFormTags.USERNAME)
            .assertEditableTextEquals("operator")
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .assertEditableTextEquals("")

        compose.onNodeWithTag(ConnectionFormTags.DISPLAY_NAME)
            .performTextReplacement("  Renamed lab  ")
        compose.onNodeWithTag(ConnectionFormTags.UPDATE_PROFILE).performClick()
        compose.waitForIdle()
        assertEquals(original.id, updatedProfile?.first)
        assertEquals("Renamed lab", updatedProfile?.second?.displayName)
        assertEquals(HostEndpoint("lab.example", 2200), updatedProfile?.second?.endpoint)

        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .performTextReplacement("delete-me")
        compose.onNodeWithTag(ConnectionFormTags.DELETE_PROFILE_PREFIX + original.id).performClick()
        assertNull(deletedId)
        compose.onNodeWithText("Delete saved profile?").assertExists()
        compose.onAllNodesWithText("operator@lab.example:2200").assertCountEquals(2)
        compose.onNodeWithTag(ConnectionFormTags.CONFIRM_DELETE_PROFILE).performClick()
        compose.waitForIdle()

        assertEquals(original.id, deletedId)
        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + original.id)
            .assertIsNotSelected()
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .assertEditableTextEquals("")
    }

    @Test
    fun changedHostTrustCanOnlyBeForgottenAfterExplicitConfirmation() {
        val trustedHost = KnownHostMetadata(
            endpointKey = "changed.example:22",
            hostname = "changed.example",
            port = 22,
            algorithm = "ssh-ed25519",
            fingerprint = "SHA256:trusted",
            firstSeenAtMillis = 1,
            lastSeenAtMillis = 2,
        )
        val hosts = mutableStateOf(listOf(trustedHost))
        var deletedEndpointKey: String? = null

        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }
            MaterialTheme {
                HostForm(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sessionError = dev.threadline.core.model.SessionError.HostKeyChanged(
                        endpoint = HostEndpoint("changed.example", 22),
                        previousFingerprint = "SHA256:trusted",
                        presentedFingerprint = "SHA256:presented",
                    ),
                    knownHosts = hosts.value,
                    onDeleteKnownHost = { endpointKey ->
                        deletedEndpointKey = endpointKey
                        hosts.value = emptyList()
                    },
                    onPrepared = { true },
                )
            }
        }

        compose.onNodeWithText("To replace this trust record", substring = true).assertExists()
        compose.onNodeWithTag(ConnectionFormTags.DELETE_TRUST_PREFIX + trustedHost.endpointKey)
            .performClick()
        assertNull(deletedEndpointKey)
        compose.onNodeWithText("Forget trusted server?").assertExists()
        compose.onAllNodesWithText("ssh-ed25519 · SHA256:trusted").assertCountEquals(2)

        compose.onNodeWithTag(ConnectionFormTags.CONFIRM_DELETE_TRUST).performClick()
        compose.waitForIdle()

        assertEquals(trustedHost.endpointKey, deletedEndpointKey)
        compose.onAllNodesWithTag(
            ConnectionFormTags.TRUSTED_HOST_PREFIX + trustedHost.endpointKey,
        ).assertCountEquals(0)
    }

    @Test
    fun authenticationFailureIsAssertiveActionableAndFocusesTheClearedCredential() {
        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }
            MaterialTheme {
                HostForm(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sessionError = dev.threadline.core.model.SessionError.AuthenticationRejected,
                    onPrepared = { true },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SESSION_ERROR).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            ),
        )
        compose.onNodeWithText("Authentication failed", useUnmergedTree = true).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        compose.onNodeWithText("Passwords and passphrases are cleared", substring = true)
            .assertExists()

        compose.onNodeWithTag(ConnectionFormTags.ERROR_ACTION).performClick()

        compose.onNodeWithTag(ConnectionFormTags.PASSWORD).assertIsFocused()
    }

    @Test
    fun networkFailureReviewActionFocusesTheHostname() {
        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }
            MaterialTheme {
                HostForm(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sessionError = dev.threadline.core.model.SessionError.ConnectionTimedOut,
                    onPrepared = { true },
                )
            }
        }

        compose.onNodeWithText("Connection timed out").assertExists()
        compose.onNodeWithText("Check the hostname, port, network or VPN", substring = true)
            .assertExists()
        compose.onNodeWithTag(ConnectionFormTags.ERROR_ACTION).performClick()

        compose.onNodeWithTag(ConnectionFormTags.HOSTNAME).assertIsFocused()
    }

    @Test
    fun notificationFailureOnlyOpensSettingsAfterTheUserAction() {
        var settingsOpenCount = 0
        compose.setContent {
            var draft by rememberSaveable(stateSaver = ConnectionFormDraft.Saver) {
                mutableStateOf(ConnectionFormDraft.fixtureDefaults())
            }
            MaterialTheme {
                HostForm(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sessionError =
                    dev.threadline.core.model.SessionError.NotificationPermissionRequired,
                    onOpenNotificationSettings = { settingsOpenCount += 1 },
                    onPrepared = { true },
                )
            }
        }

        assertEquals(0, settingsOpenCount)
        compose.onNodeWithText("Open notification settings").performClick()
        compose.runOnIdle { assertEquals(1, settingsOpenCount) }
    }
}

private fun transcriptSummary(
    id: String,
    name: String,
) = SavedTranscriptSessionSummary(
    id = id,
    displayName = name,
    hostname = "fixture.test",
    port = 2222,
    username = "threadline",
    startedAtMillis = 1,
    endedAtMillis = 2,
    turnsTruncated = false,
    turnCount = 1,
)

private fun transcriptSession(id: String) = SavedTranscriptSession(
    summary = transcriptSummary(id, if (id == "session-1") "First" else "Second"),
    turns = listOf(
        SavedTranscriptTurn(
            turn = CommandTurn(
                id = CommandId("command-$id"),
                command = "printf saved",
                directoryAtStart = "/tmp",
                submittedAtMillis = 1,
                startedAtMillis = 1,
                completedAtMillis = 2,
                status = CommandStatus.SUCCEEDED,
                exitStatus = 0,
                currentDirectory = "/tmp",
                output = CommandOutput("saved output"),
            ),
            commandTruncated = false,
        ),
    ),
)

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertEditableTextEquals(
    expected: String,
) = assert(
    SemanticsMatcher.expectValue(
        SemanticsProperties.EditableText,
        AnnotatedString(expected),
    ),
)
