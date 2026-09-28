package dev.threadline

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import dev.threadline.core.model.ConnectionRequest
import dev.threadline.core.model.SessionCredential
import dev.threadline.data.identity.IdentityAuthenticationMethod
import dev.threadline.data.identity.SshIdentity
import dev.threadline.data.key.ImportedPrivateKeyMetadata
import dev.threadline.data.key.InvalidImportedPrivateKeyException
import dev.threadline.data.key.SavedSshPasswordAuthenticationCancelledException
import dev.threadline.data.profile.SavedHostProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HomeDirectConnectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readySavedKeyPreparesFromOneHomeTapAtLargeFontScale() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val identity = identity(IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY)
        var prepared: ConnectionRequest? = null
        var loadCount = 0
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults().copy(ephemeral = true))

        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    HostForm(
                        draft = draft.value,
                        onDraftChange = { draft.value = it },
                        sessionError = null,
                        initialTask = HomeTask.OVERVIEW,
                        hostProfiles = listOf(profile),
                        sshIdentities = listOf(identity),
                        importedPrivateKeys = listOf(savedKey()),
                        onLoadPrivateKey = { id, passphrase ->
                            assertEquals(KEY_ID, id)
                            assertNull(passphrase)
                            loadCount += 1
                            SessionCredential.PrivateKey.from(byteArrayOf(1, 2, 3), null)
                        },
                        onPrepared = {
                            prepared = it
                            true
                        },
                    )
                }
            }
        }

        compose.runOnIdle { assertNull(prepared) }
        compose.onNodeWithText("operator@lab.example:22").assertExists()
        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, loadCount)
            assertEquals("operator", requireNotNull(prepared).profile.username)
            assertEquals("lab.example", requireNotNull(prepared).profile.endpoint.hostname)
            assertFalse(requireNotNull(prepared).ephemeral)
            requireNotNull(prepared).credential.clear()
        }
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).assertDoesNotExist()
    }

    @Test
    fun savedPasswordUnlocksFromHomeWithoutOpeningTheEditor() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        var unlockCount = 0
        var prepared: ConnectionRequest? = null

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = ConnectionFormDraft.emptyDefaults(),
                    onDraftChange = {},
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    sshIdentities = listOf(
                        identity(IdentityAuthenticationMethod.PASSWORD, hasSavedPassword = true),
                    ),
                    savedPasswordSupported = true,
                    onLoadSavedSshPassword = { id ->
                        assertEquals(IDENTITY_ID, id)
                        unlockCount += 1
                        SessionCredential.Password.from("fixture-password".toCharArray())
                    },
                    onPrepared = {
                        prepared = it
                        true
                    },
                )
            }
        }

        compose.runOnIdle { assertNull(prepared) }
        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, unlockCount)
            assertEquals("operator", requireNotNull(prepared).profile.username)
            requireNotNull(prepared).credential.clear()
        }
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).assertDoesNotExist()
    }

    @Test
    fun canceledSavedPasswordOpensManualEntryBeforeConnecting() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val selectedProfileId = mutableStateOf<String?>(null)
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())
        var unlockCount = 0
        var prepared: ConnectionRequest? = null

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    selectedHostProfileId = selectedProfileId.value,
                    onSelectedHostProfileChange = { selectedProfileId.value = it },
                    sshIdentities = listOf(
                        identity(IdentityAuthenticationMethod.PASSWORD, hasSavedPassword = true),
                    ),
                    savedPasswordSupported = true,
                    onLoadSavedSshPassword = {
                        unlockCount += 1
                        throw SavedSshPasswordAuthenticationCancelledException()
                    },
                    onPrepared = {
                        prepared = it
                        true
                    },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performClick()
        compose.onNodeWithText("Complete sign-in").assertExists()
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD).assertIsFocused()
        compose.runOnIdle {
            assertEquals(1, unlockCount)
            assertNull(prepared)
        }

        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .performTextInput("fixture-password")
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, unlockCount)
            val credential = requireNotNull(prepared).credential as SessionCredential.Password
            assertArrayEquals("fixture-password".toCharArray(), credential.characters)
            credential.clear()
        }
    }

    @Test
    fun encryptedKeyRequestsPassphraseBeforeStartingSsh() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val selectedProfileId = mutableStateOf<String?>(null)
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())
        var prepared: ConnectionRequest? = null
        var loadCount = 0

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    selectedHostProfileId = selectedProfileId.value,
                    onSelectedHostProfileChange = { selectedProfileId.value = it },
                    sshIdentities = listOf(
                        identity(IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY),
                    ),
                    importedPrivateKeys = listOf(savedKey()),
                    onLoadPrivateKey = { _, passphrase ->
                        loadCount += 1
                        if (passphrase == null) {
                            throw InvalidImportedPrivateKeyException(
                                IllegalArgumentException("fixture passphrase required"),
                            )
                        }
                        assertArrayEquals("fixture-passphrase".toCharArray(), passphrase)
                        SessionCredential.PrivateKey.from(byteArrayOf(1, 2, 3), passphrase)
                    },
                    onPrepared = {
                        prepared = it
                        true
                    },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performClick()
        compose.onNodeWithText("Complete sign-in").assertExists()
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE).assertIsFocused()
        compose.runOnIdle {
            assertEquals(1, loadCount)
            assertNull(prepared)
        }

        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .performTextInput("fixture-passphrase")
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, loadCount)
            assertEquals("operator", requireNotNull(prepared).profile.username)
            requireNotNull(prepared).credential.clear()
        }
    }

    @Test
    fun changeIdentityIsExplicitAndDoesNotUpdateTheSavedPreference() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val alternate = identity(
            method = IdentityAuthenticationMethod.PASSWORD,
            id = "alternate-id",
            username = "alternate",
        )
        val selectedProfileId = mutableStateOf<String?>(null)
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())
        var prepared: ConnectionRequest? = null
        var updateCount = 0

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    selectedHostProfileId = selectedProfileId.value,
                    onSelectedHostProfileChange = { selectedProfileId.value = it },
                    sshIdentities = listOf(
                        identity(IdentityAuthenticationMethod.UNCONFIGURED),
                        alternate,
                    ),
                    onUpdateHostProfile = { _, _, _ -> updateCount += 1 },
                    onPrepared = {
                        prepared = it
                        true
                    },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.CHANGE_IDENTITY_PREFIX + profile.id)
            .performClick()
        compose.onNodeWithTag(ConnectionFormTags.PREFERRED_IDENTITY).assertIsDisplayed()
        compose.onNodeWithTag(ConnectionFormTags.UPDATE_PROFILE).assertDoesNotExist()
        compose.runOnIdle { assertNull(prepared) }

        compose.onNodeWithTag(ConnectionFormTags.PREFERRED_IDENTITY).performClick()
        compose.onNodeWithText("Alternate identity").performClick()
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD).performTextInput("one-time")
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("alternate", requireNotNull(prepared).profile.username)
            assertEquals(0, updateCount)
            assertEquals(IDENTITY_ID, profile.preferredIdentityId)
            requireNotNull(prepared).credential.clear()
        }
    }

    @Test
    fun missingSavedKeyOpensRepairWithoutLoadingOrConnecting() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val selectedProfileId = mutableStateOf<String?>(null)
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    selectedHostProfileId = selectedProfileId.value,
                    onSelectedHostProfileChange = { selectedProfileId.value = it },
                    sshIdentities = listOf(
                        identity(IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY),
                    ),
                    onLoadPrivateKey = { _, _ ->
                        error("A missing key must not be loaded.")
                    },
                    onPrepared = { error("A missing key must not start SSH.") },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performClick()
        compose.onNodeWithText("Complete sign-in").assertExists()
        compose.onNodeWithText("The saved private key is unavailable", substring = true)
            .assertExists()
        compose.onNodeWithTag(ConnectionFormTags.CHOOSE_PRIVATE_KEY)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun unconfiguredIdentityOpensTheIdentityPickerPath() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val selectedProfileId = mutableStateOf<String?>(null)
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    selectedHostProfileId = selectedProfileId.value,
                    onSelectedHostProfileChange = { selectedProfileId.value = it },
                    sshIdentities = listOf(identity(IdentityAuthenticationMethod.UNCONFIGURED)),
                    onPrepared = { error("An unconfigured identity must not start SSH.") },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performClick()
        compose.onNodeWithText("Choose a configured SSH identity for this connection.")
            .assertExists()
        compose.onNodeWithTag(ConnectionFormTags.PREFERRED_IDENTITY).assertIsDisplayed()
        compose.onNodeWithTag(ConnectionFormTags.UPDATE_PROFILE).assertDoesNotExist()
    }

    @Test
    fun unavailableSavedPasswordOpensManualEntryWithoutPrompting() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val selectedProfileId = mutableStateOf<String?>(null)
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    selectedHostProfileId = selectedProfileId.value,
                    onSelectedHostProfileChange = { selectedProfileId.value = it },
                    sshIdentities = listOf(
                        identity(IdentityAuthenticationMethod.PASSWORD, hasSavedPassword = true),
                    ),
                    savedPasswordSupported = false,
                    onLoadSavedSshPassword = {
                        error("Unsupported devices must not request saved-password approval.")
                    },
                    onPrepared = { error("Manual entry is required first.") },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performClick()
        compose.onNodeWithText("The saved password is unavailable here", substring = true)
            .assertExists()
        compose.onNodeWithTag(ConnectionFormTags.PASSWORD).assertIsFocused()
    }

    @Test
    fun homeTapWaitsForRequiredNotificationPermissionBeforeLoadingCredentials() {
        val profile = profile(preferredIdentityId = IDENTITY_ID)
        val selectedProfileId = mutableStateOf<String?>(null)
        val draft = mutableStateOf(ConnectionFormDraft.emptyDefaults())

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = draft.value,
                    onDraftChange = { draft.value = it },
                    sessionError = null,
                    initialTask = HomeTask.OVERVIEW,
                    hostProfiles = listOf(profile),
                    selectedHostProfileId = selectedProfileId.value,
                    onSelectedHostProfileChange = { selectedProfileId.value = it },
                    sshIdentities = listOf(
                        identity(IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY),
                    ),
                    importedPrivateKeys = listOf(savedKey()),
                    notificationPermissionState = SessionNotificationPermissionState.REQUESTABLE,
                    onLoadPrivateKey = { _, _ ->
                        error("Credentials must wait for notification permission.")
                    },
                    onPrepared = { error("SSH must wait for notification permission.") },
                )
            }
        }

        compose.onNodeWithTag(ConnectionFormTags.SAVED_PROFILE_PREFIX + profile.id)
            .performClick()
        compose.onNodeWithTag(ConnectionFormTags.NOTIFICATION_PERMISSION)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun profile(preferredIdentityId: String) = SavedHostProfile(
        id = "saved-host",
        displayName = "Lab",
        hostname = "lab.example",
        port = 22,
        username = "old-user",
        createdAtMillis = 1,
        updatedAtMillis = 1,
        preferredIdentityId = preferredIdentityId,
    )

    private fun identity(
        method: IdentityAuthenticationMethod,
        id: String = IDENTITY_ID,
        username: String = "operator",
        hasSavedPassword: Boolean = false,
    ) = SshIdentity(
        id = id,
        label = if (id == IDENTITY_ID) "Saved identity" else "Alternate identity",
        username = username,
        authenticationMethod = method,
        importedPrivateKeyId = if (method == IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY) {
            KEY_ID
        } else {
            null
        },
        createdAtMillis = 1,
        updatedAtMillis = 1,
        hasSavedPassword = hasSavedPassword,
    )

    private fun savedKey() = ImportedPrivateKeyMetadata(
        id = KEY_ID,
        displayName = "Saved key",
        format = "OpenSSH",
        keyType = "ssh-rsa",
        publicKeyFingerprint = "fixture fingerprint",
        createdAtMillis = 1,
    )

    private companion object {
        const val IDENTITY_ID = "saved-identity"
        const val KEY_ID = "saved-key"
    }
}
