package dev.threadline

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal class OnboardingPreferences(
    private val preferences: SharedPreferences,
) {
    fun shouldShowIntroduction(): Boolean =
        preferences.getInt(COMPLETED_VERSION_KEY, 0) < CURRENT_VERSION

    fun markIntroductionComplete() {
        preferences.edit().putInt(COMPLETED_VERSION_KEY, CURRENT_VERSION).apply()
    }

    companion object {
        internal const val CURRENT_VERSION = 1
        private const val PREFERENCES_NAME = "onboarding"
        private const val COMPLETED_VERSION_KEY = "completed_version"

        fun create(context: Context) = OnboardingPreferences(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
    }
}

internal object OnboardingTags {
    const val SCREEN = "onboarding-screen"
    const val CONTINUE = "onboarding-continue"
}

@Composable
internal fun OnboardingScreen(
    onContinue: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Text(
                    text = "Welcome to Threadline",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .semantics { heading() },
                )
                HorizontalDivider()
            }
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag(OnboardingTags.CONTINUE),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text("Continue to connections")
                }
            }
        },
        modifier = Modifier.testTag(OnboardingTags.SCREEN),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Run ordinary SSH commands as a readable transcript, with a real terminal " +
                    "ready when a program needs one.",
                style = MaterialTheme.typography.titleMedium,
            )
            IntroductionPoint(
                title = "Transcript first",
                body = "Send ordinary commands from the composer. Each result becomes a card " +
                    "with its status, exit code and working directory.",
            )
            IntroductionPoint(
                title = "Same-session terminal",
                body = "Open Terminal at any time—or when Threadline suggests it for an " +
                    "interactive program. It uses the same live shell and connection.",
            )
            IntroductionPoint(
                title = "Direct and verified",
                body = "Threadline connects from this device to the SSH endpoint you provide. " +
                    "Verify unknown host fingerprints; changed host keys are blocked.",
            )
            IntroductionPoint(
                title = "Local by default",
                body = "Profiles save connection details, never passwords or passphrases. " +
                    "Transcripts stay on this device unless you choose an ephemeral session.",
            )
        }
    }
}

@Composable
private fun IntroductionPoint(
    title: String,
    body: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
