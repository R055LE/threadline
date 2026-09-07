package dev.threadline

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
    modifier: Modifier = Modifier,
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
            Surface(
                shadowElevation = 4.dp,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag(OnboardingTags.CONTINUE),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text("Continue")
                }
            }
        },
        modifier = modifier.testTag(OnboardingTags.SCREEN),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Threadline keeps one SSH session readable and under your control.",
                style = MaterialTheme.typography.titleMedium,
            )
            IntroductionPoint(
                title = "Transcript first",
                body = "Ordinary commands run in the transcript.",
            )
            IntroductionPoint(
                title = "Same-session terminal",
                body = "Interactive programs open in Terminal without leaving the live SSH session.",
            )
            IntroductionPoint(
                title = "Verify the server",
                body = "Threadline connects directly to the SSH endpoint you enter. Verify unknown " +
                    "fingerprints; changed host keys are blocked.",
            )
            IntroductionPoint(
                title = "Local retention",
                body = "Profiles never save passwords or passphrases. Transcripts stay on this " +
                    "device unless you use an ephemeral session.",
            )
        }
    }
}

@Composable
private fun IntroductionPoint(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}
