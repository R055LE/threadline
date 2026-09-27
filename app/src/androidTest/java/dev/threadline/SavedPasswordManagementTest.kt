package dev.threadline

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.threadline.data.identity.IdentityAuthenticationMethod
import dev.threadline.data.identity.SshIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SavedPasswordManagementTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun saveAndReplaceRequireExplicitActionAndRemovalIsConfirmationGated() {
        val identities = mutableStateOf(listOf(identity()))
        var savedIdentityId: String? = null
        var savedPassword: CharArray? = null
        var deletedIdentityId: String? = null

        compose.setContent {
            MaterialTheme {
                SshIdentityManagementContent(
                    identities = identities.value,
                    importedPrivateKeys = emptyList(),
                    savedPasswordSupported = true,
                    enabled = true,
                    onSaveIdentity = {},
                    onSaveSavedPassword = { id, password ->
                        savedIdentityId = id
                        savedPassword = password.copyOf()
                        identities.value = listOf(identity(hasSavedPassword = true))
                    },
                    onDeleteSavedPassword = { id ->
                        deletedIdentityId = id
                        identities.value = listOf(identity(hasSavedPassword = false))
                    },
                    onDeleteIdentity = { error("Password removal must keep the identity.") },
                )
            }
        }

        compose.onNodeWithTag(IdentityTags.EDIT_PREFIX + IDENTITY_ID).performClick()
        compose.onNodeWithTag(IdentityTags.PASSWORD_VALUE).performTextInput("first-secret")
        compose.onNodeWithTag(IdentityTags.SAVE_PASSWORD).performScrollTo().performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(IDENTITY_ID, savedIdentityId)
            assertArrayEquals("first-secret".toCharArray(), savedPassword)
            savedPassword?.fill('\u0000')
        }
        compose.onNodeWithText("Saved password · device approval required for each connection")
            .assertExists()

        compose.onNodeWithTag(IdentityTags.EDIT_PREFIX + IDENTITY_ID).performClick()
        compose.onNodeWithTag(IdentityTags.PASSWORD_VALUE).performTextInput("replacement-secret")
        compose.onNodeWithTag(IdentityTags.SAVE_PASSWORD).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(IDENTITY_ID, savedIdentityId)
            assertArrayEquals("replacement-secret".toCharArray(), savedPassword)
            savedPassword?.fill('\u0000')
        }

        compose.onNodeWithTag(IdentityTags.DELETE_PASSWORD_PREFIX + IDENTITY_ID).performClick()
        compose.onNodeWithText("Remove saved password?").assertIsDisplayed()
        compose.runOnIdle { assertNull(deletedIdentityId) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(deletedIdentityId) }

        compose.onNodeWithTag(IdentityTags.DELETE_PASSWORD_PREFIX + IDENTITY_ID).performClick()
        compose.onNodeWithTag(IdentityTags.CONFIRM_DELETE_PASSWORD).performClick()
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(IDENTITY_ID, deletedIdentityId) }
        compose.onNodeWithTag(IdentityTags.EDIT_PREFIX + IDENTITY_ID).assertExists()
        compose.onNodeWithText("Saved password · device approval required for each connection")
            .assertDoesNotExist()
    }

    @Test
    fun olderDevicesKeepManualPasswordEntryAndCannotSaveOne() {
        compose.setContent {
            MaterialTheme {
                SshIdentityManagementContent(
                    identities = listOf(identity()),
                    importedPrivateKeys = emptyList(),
                    savedPasswordSupported = false,
                    enabled = true,
                    onSaveIdentity = {},
                    onSaveSavedPassword = { _, _ -> error("Must stay session-only.") },
                    onDeleteSavedPassword = {},
                    onDeleteIdentity = {},
                )
            }
        }

        compose.onNodeWithTag(IdentityTags.EDIT_PREFIX + IDENTITY_ID).performClick()
        compose.onNodeWithText(
            "Saved passwords need Android 11 or newer and a secure screen lock.",
            substring = true,
        )
            .assertExists()
        compose.onNodeWithTag(IdentityTags.PASSWORD_VALUE).assertDoesNotExist()
        compose.onNodeWithTag(IdentityTags.SAVE_PASSWORD).assertDoesNotExist()
    }

    private fun identity(hasSavedPassword: Boolean = false) = SshIdentity(
        id = IDENTITY_ID,
        label = "Fixture identity",
        username = "operator",
        authenticationMethod = IdentityAuthenticationMethod.PASSWORD,
        importedPrivateKeyId = null,
        createdAtMillis = 1,
        updatedAtMillis = 1,
        hasSavedPassword = hasSavedPassword,
    )

    private companion object {
        const val IDENTITY_ID = "identity-id"
    }
}
