package dev.threadline

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import dev.threadline.core.model.SessionCredential
import dev.threadline.data.key.ImportedPrivateKeyMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionFormRetentionTest {
    @get:Rule
    val compose = createComposeRule()

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
        compose.onNodeWithTag(ConnectionFormTags.CONNECT).performClick()
        compose.waitForIdle()

        assertNotNull(preparedCredential)
        assertTrue(requireNotNull(receivedPassphrase).all { it == '\u0000' })
        compose.onNodeWithTag(ConnectionFormTags.KEY_PASSPHRASE)
            .assertEditableTextEquals("")
        preparedCredential?.clear()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertEditableTextEquals(
    expected: String,
) = assert(
    SemanticsMatcher.expectValue(
        SemanticsProperties.EditableText,
        AnnotatedString(expected),
    ),
)
