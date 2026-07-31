package dev.threadline

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.threadline.core.diagnostics.DiagnosticEnvironment
import dev.threadline.core.diagnostics.MAX_SENSITIVE_COMMANDS
import dev.threadline.core.diagnostics.SensitiveDiagnosticCommand
import dev.threadline.core.diagnostics.SensitiveDiagnosticDetails
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.CommandTranscriptState

internal object DiagnosticTags {
    const val OPEN = "diagnostics-open"
    const val INCLUDE_SENSITIVE = "diagnostics-include-sensitive"
    const val PREVIEW = "diagnostics-preview"
    const val SHARE = "diagnostics-share"
}

internal fun androidDiagnosticEnvironment(context: Context): DiagnosticEnvironment {
    @Suppress("DEPRECATION")
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return DiagnosticEnvironment(
        appVersionName = packageInfo.versionName ?: "unknown",
        appVersionCode = versionCode,
        androidSdk = Build.VERSION.SDK_INT,
        deviceManufacturer = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
    )
}

internal fun diagnosticShareIntent(report: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "Threadline diagnostic report")
    putExtra(Intent.EXTRA_TEXT, report)
}

internal fun shareDiagnosticReport(context: Context, report: String) {
    context.startActivity(
        Intent.createChooser(
            diagnosticShareIntent(report),
            "Share Threadline diagnostics",
        ),
    )
}

internal fun sensitiveDiagnosticDetails(
    draft: ConnectionFormDraft,
    structuredShell: StructuredShellState,
    transcript: CommandTranscriptState,
): SensitiveDiagnosticDetails = SensitiveDiagnosticDetails(
    displayName = draft.displayName,
    hostname = draft.hostname,
    port = draft.port.toIntOrNull()?.takeIf { it in 1..65535 },
    username = draft.username,
    currentDirectory = when (structuredShell) {
        is StructuredShellState.Ready -> structuredShell.currentDirectory
        is StructuredShellState.Running -> structuredShell.activeCommand.directoryAtStart
        StructuredShellState.Inactive,
        is StructuredShellState.Bootstrapping,
        is StructuredShellState.Unavailable,
        -> null
    },
    recentCommands = transcript.turns.takeLast(MAX_SENSITIVE_COMMANDS).map { turn ->
        SensitiveDiagnosticCommand(
            status = turn.status,
            directory = turn.currentDirectory ?: turn.directoryAtStart,
            command = turn.command,
        )
    },
)

@Composable
internal fun DiagnosticReportDialog(
    reportFactory: (includeSensitiveDetails: Boolean) -> String,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
) {
    var includeSensitiveDetails by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf(false) }
    val report = reportFactory(includeSensitiveDetails)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostic report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Review the exact text before sharing. By default, Threadline excludes " +
                        "host fields, usernames, directories, commands, output, credentials, " +
                        "and host-key material.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = includeSensitiveDetails,
                        onCheckedChange = {
                            includeSensitiveDetails = it
                            shareError = false
                        },
                        modifier = Modifier.testTag(DiagnosticTags.INCLUDE_SENSITIVE),
                    )
                    Text("Include host fields, directories, and recent command text")
                }
                if (includeSensitiveDetails) {
                    Text(
                        "This can expose server names, usernames, paths, and secrets typed " +
                            "inside commands. Command output and credentials are still excluded.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                shareError.takeIf { it }?.let {
                    Text(
                        "No app accepted the diagnostic report.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SelectionContainer {
                    Text(
                        text = report,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                            .testTag(DiagnosticTags.PREVIEW),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    shareError = try {
                        onShare(report)
                        false
                    } catch (_: RuntimeException) {
                        true
                    }
                },
                modifier = Modifier.testTag(DiagnosticTags.SHARE),
            ) {
                Text(if (includeSensitiveDetails) "Share with details" else "Share report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
