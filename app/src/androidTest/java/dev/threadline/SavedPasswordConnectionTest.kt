package dev.threadline

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.threadline.core.model.SessionCredential
import dev.threadline.data.identity.IdentityAuthenticationMethod
import dev.threadline.data.identity.SshIdentity
import dev.threadline.data.key.SavedSshPasswordAuthenticationCancelledException
import dev.threadline.data.key.SavedSshPasswordKeyInvalidatedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SavedPasswordConnectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savedCredentialIsLoadedForEachConnectionAttempt() {
        var authorizationCount = 0
        var preparedCredential: SessionCredential.Password? = null

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = ConnectionFormDraft.fixtureDefaults(),
                    onDraftChange = {},
                    sessionError = null,
                    sshIdentities = listOf(savedIdentity()),
                    savedPasswordSupported = true,
                    onLoadSavedSshPassword = { identityId ->
                        assertEquals(IDENTITY_ID, identityId)
                        authorizationCount += 1
                        val password = "saved-password".toCharArray()
                        try {
                            SessionCredential.Password.from(password)
                        } finally {
                            password.fill('\u0000')
                        }
                    },
                    onPrepared = { request ->
                        preparedCredential = request.credential as SessionCredential.Password
                        true
                    },
                )
            }
        }

        selectIdentity()
        repeat(2) { attempt ->
            compose.onNodeWithTag(ConnectionFormTags.CONNECT)
                .performScrollTo()
                .performClick()
            compose.waitForIdle()

            compose.runOnIdle {
                val credential = requireNotNull(preparedCredential)
                assertEquals(attempt + 1, authorizationCount)
                assertArrayEquals("saved-password".toCharArray(), credential.characters)
                credential.clear()
                assertArrayEquals(CharArray("saved-password".length), credential.characters)
            }
        }
    }

    @Test
    fun manualPasswordBypassesUnlockAndCanceledUnlockFallsBackToManualEntry() {
        var authorizationCount = 0
        var preparedCredential: SessionCredential.Password? = null
        var cancelFirstAttempt = true

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = ConnectionFormDraft.fixtureDefaults(),
                    onDraftChange = {},
                    sessionError = null,
                    sshIdentities = listOf(savedIdentity()),
                    savedPasswordSupported = true,
                    onLoadSavedSshPassword = {
                        authorizationCount += 1
                        if (cancelFirstAttempt) {
                            cancelFirstAttempt = false
                            throw SavedSshPasswordAuthenticationCancelledException()
                        }
                        error("A manual password must not try the saved password again.")
                    },
                    onPrepared = { request ->
                        preparedCredential = request.credential as SessionCredential.Password
                        true
                    },
                )
            }
        }

        selectIdentity()
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitUntil {
            compose.onAllNodesWithText(
                "Device approval was canceled. Enter the password for this connection or try again.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertNull(preparedCredential) }

        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .performScrollTo()
            .performTextInput("manual-password")
        compose.onNodeWithText(
            "Device approval was canceled. Enter the password for this connection or try again.",
        ).assertDoesNotExist()
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            val credential = requireNotNull(preparedCredential)
            assertEquals(1, authorizationCount)
            assertArrayEquals(
                "manual-password".toCharArray(),
                credential.characters,
            )
            credential.clear()
            assertArrayEquals(
                CharArray("manual-password".length),
                credential.characters,
            )
        }
    }

    @Test
    fun invalidatedSavedPasswordCannotPrepareConnectionUntilManualEntry() {
        var authorizationCount = 0
        var preparedCredential: SessionCredential.Password? = null

        compose.setContent {
            MaterialTheme {
                HostForm(
                    draft = ConnectionFormDraft.fixtureDefaults(),
                    onDraftChange = {},
                    sessionError = null,
                    sshIdentities = listOf(savedIdentity()),
                    savedPasswordSupported = true,
                    onLoadSavedSshPassword = {
                        authorizationCount += 1
                        throw SavedSshPasswordKeyInvalidatedException(
                            IllegalStateException("test invalidation"),
                        )
                    },
                    onPrepared = { request ->
                        preparedCredential = request.credential as SessionCredential.Password
                        true
                    },
                )
            }
        }

        selectIdentity()
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitUntil {
            compose.onAllNodesWithText(
                "Device security changed. Enter the password again to replace the saved copy.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(1, authorizationCount)
            assertNull(preparedCredential)
        }

        compose.onNodeWithTag(ConnectionFormTags.PASSWORD)
            .performScrollTo()
            .performTextInput("manual-password")
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performScrollTo().performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            val credential = requireNotNull(preparedCredential)
            assertEquals(1, authorizationCount)
            assertArrayEquals("manual-password".toCharArray(), credential.characters)
            credential.clear()
        }
    }

    private fun selectIdentity() {
        compose.onNodeWithTag(ConnectionFormTags.PREFERRED_IDENTITY)
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Saved identity").performClick()
    }

    private fun savedIdentity() = SshIdentity(
        id = IDENTITY_ID,
        label = "Saved identity",
        username = "operator",
        authenticationMethod = IdentityAuthenticationMethod.PASSWORD,
        importedPrivateKeyId = null,
        createdAtMillis = 1,
        updatedAtMillis = 1,
        hasSavedPassword = true,
    )

    private companion object {
        const val IDENTITY_ID = "saved-identity"
    }
}
