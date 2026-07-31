package dev.threadline.core.diagnostics

import dev.threadline.core.model.ConnectionStage
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DiagnosticEnvironment(
    val appVersionName: String,
    val appVersionCode: Long,
    val androidSdk: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
)

data class DiagnosticInventory(
    val savedProfiles: Int,
    val trustedServers: Int,
    val importedPrivateKeys: Int,
    val savedTranscriptSessions: Int,
)

data class DiagnosticTranscriptSummary(
    val turnCount: Int,
    val hasActiveCommand: Boolean,
    val statusCounts: Map<CommandStatus, Int>,
    val truncatedOutputCount: Int,
    val approximateOutputCount: Int,
    val interactiveHintCount: Int,
    val retainedOutputCharacters: Long,
    val receivedOutputBytes: Long,
)

data class DiagnosticSessionSnapshot(
    val connectionState: String,
    val connectionStage: String?,
    val errorCode: String?,
    val structuredShellState: String,
    val structuredShellUnavailableReason: String?,
    val transcript: DiagnosticTranscriptSummary,
    val transcriptSaveFailed: Boolean,
)

data class SensitiveDiagnosticCommand(
    val status: CommandStatus,
    val directory: String?,
    val command: String,
)

data class SensitiveDiagnosticDetails(
    val displayName: String,
    val hostname: String,
    val port: Int?,
    val username: String,
    val currentDirectory: String?,
    val recentCommands: List<SensitiveDiagnosticCommand>,
)

data class DiagnosticReportInput(
    val generatedAtMillis: Long,
    val environment: DiagnosticEnvironment,
    val inventory: DiagnosticInventory,
    val session: DiagnosticSessionSnapshot,
    val sensitiveDetails: SensitiveDiagnosticDetails? = null,
)

fun diagnosticSessionSnapshot(
    connection: SessionState,
    structuredShell: StructuredShellState,
    transcript: CommandTranscriptState,
    transcriptSaveFailed: Boolean,
): DiagnosticSessionSnapshot {
    val turns = transcript.turns
    return DiagnosticSessionSnapshot(
        connectionState = connection.diagnosticStateCode(),
        connectionStage = (connection as? SessionState.Connecting)
            ?.stage
            ?.diagnosticCode(),
        errorCode = (connection as? SessionState.Failed)?.error?.diagnosticCode(),
        structuredShellState = structuredShell.diagnosticStateCode(),
        structuredShellUnavailableReason =
        (structuredShell as? StructuredShellState.Unavailable)
            ?.reason
            ?.name
            ?.lowercase(Locale.ROOT),
        transcript = DiagnosticTranscriptSummary(
            turnCount = turns.size,
            hasActiveCommand = transcript.activeCommandId != null,
            statusCounts = turns.groupingBy { it.status }.eachCount(),
            truncatedOutputCount = turns.count { it.output.truncated },
            approximateOutputCount = turns.count { it.output.approximate },
            interactiveHintCount = turns.count { it.output.interactiveHint != null },
            retainedOutputCharacters = turns.sumOf { it.output.plainText.length.toLong() },
            receivedOutputBytes = turns.sumOf { it.output.byteCount },
        ),
        transcriptSaveFailed = transcriptSaveFailed,
    )
}

fun generateDiagnosticReport(input: DiagnosticReportInput): String = buildString {
    appendLine("Threadline diagnostic report")
    appendLine("format_version: 1")
    appendLine("generated_at_utc: ${formatUtc(input.generatedAtMillis)}")
    appendLine(
        "sensitive_session_details_included: ${input.sensitiveDetails != null}",
    )

    appendLine()
    appendLine("[app]")
    appendDiagnosticField("version_name", input.environment.appVersionName)
    appendLine("version_code: ${input.environment.appVersionCode}")
    appendLine("android_sdk: ${input.environment.androidSdk}")
    appendDiagnosticField("device_manufacturer", input.environment.deviceManufacturer)
    appendDiagnosticField("device_model", input.environment.deviceModel)

    appendLine()
    appendLine("[session]")
    appendLine("connection_state: ${input.session.connectionState}")
    input.session.connectionStage?.let { appendLine("connection_stage: $it") }
    input.session.errorCode?.let { appendLine("error_code: $it") }
    appendLine("structured_shell_state: ${input.session.structuredShellState}")
    input.session.structuredShellUnavailableReason?.let {
        appendLine("structured_shell_unavailable_reason: $it")
    }
    appendLine("transcript_save_failed: ${input.session.transcriptSaveFailed}")

    appendLine()
    appendLine("[transcript_summary]")
    appendLine("turn_count: ${input.session.transcript.turnCount}")
    appendLine("active_command: ${input.session.transcript.hasActiveCommand}")
    CommandStatus.entries.forEach { status ->
        val code = status.name.lowercase(Locale.ROOT)
        appendLine("status_$code: ${input.session.transcript.statusCounts[status] ?: 0}")
    }
    appendLine("truncated_output_turns: ${input.session.transcript.truncatedOutputCount}")
    appendLine("approximate_output_turns: ${input.session.transcript.approximateOutputCount}")
    appendLine("interactive_hint_turns: ${input.session.transcript.interactiveHintCount}")
    appendLine(
        "retained_output_characters: ${input.session.transcript.retainedOutputCharacters}",
    )
    appendLine("received_output_bytes: ${input.session.transcript.receivedOutputBytes}")

    appendLine()
    appendLine("[local_inventory]")
    appendLine("saved_profiles: ${input.inventory.savedProfiles}")
    appendLine("trusted_servers: ${input.inventory.trustedServers}")
    appendLine("imported_private_keys: ${input.inventory.importedPrivateKeys}")
    appendLine("saved_transcript_sessions: ${input.inventory.savedTranscriptSessions}")

    appendLine()
    appendLine("[privacy]")
    appendLine(
        "host_profile: ${if (input.sensitiveDetails == null) "redacted" else "included_by_user"}",
    )
    appendLine(
        "command_content: ${if (input.sensitiveDetails == null) "redacted" else "included_by_user"}",
    )
    appendLine("command_output: never_included")
    appendLine("credentials_and_key_material: never_included")
    appendLine("host_keys_and_fingerprints: never_included")

    input.sensitiveDetails?.let { details ->
        appendLine()
        appendLine("[sensitive_session_details]")
        appendDiagnosticField("display_name", details.displayName)
        appendDiagnosticField("hostname", details.hostname)
        details.port?.let { appendLine("port: $it") }
        appendDiagnosticField("username", details.username)
        details.currentDirectory?.let { appendDiagnosticField("current_directory", it) }
        val commands = details.recentCommands.takeLast(MAX_SENSITIVE_COMMANDS)
        appendLine("recent_command_count: ${commands.size}")
        commands.forEachIndexed { index, command ->
            val field = "command_${index + 1}"
            appendLine("${field}_status: ${command.status.name.lowercase(Locale.ROOT)}")
            command.directory?.let { appendDiagnosticField("${field}_directory", it) }
            appendDiagnosticField("${field}_text", command.command, MAX_COMMAND_FIELD_CHARS)
        }
    }
}.take(MAX_REPORT_CHARS)

private fun SessionState.diagnosticStateCode(): String = when (this) {
    SessionState.Disconnected -> "disconnected"
    is SessionState.Connecting -> "connecting"
    is SessionState.AwaitingHostKey -> "awaiting_host_key"
    is SessionState.Connected -> "connected"
    is SessionState.Disconnecting -> "disconnecting"
    is SessionState.Failed -> "failed"
}

private fun ConnectionStage.diagnosticCode(): String = name.lowercase(Locale.ROOT)

private fun StructuredShellState.diagnosticStateCode(): String = when (this) {
    StructuredShellState.Inactive -> "inactive"
    is StructuredShellState.Bootstrapping -> "bootstrapping"
    is StructuredShellState.Ready -> "ready"
    is StructuredShellState.Running -> "running"
    is StructuredShellState.Unavailable -> "unavailable"
}

private fun SessionError.diagnosticCode(): String = when (this) {
    is SessionError.HostKeyRejected -> "host_key_rejected"
    is SessionError.HostKeyChanged -> "host_key_changed"
    SessionError.KnownHostStorageFailed -> "known_host_storage_failed"
    SessionError.AuthenticationRejected -> "authentication_rejected"
    SessionError.UnsupportedPrivateKey -> "unsupported_private_key"
    SessionError.PtyRejected -> "pty_rejected"
    SessionError.ShellRejected -> "shell_rejected"
    SessionError.PtyResizeRejected -> "pty_resize_rejected"
    SessionError.ConnectionFailed -> "connection_failed"
    SessionError.ProtocolMismatch -> "protocol_mismatch"
    SessionError.ConnectionLost -> "connection_lost"
    SessionError.InputBackpressure -> "input_backpressure"
    SessionError.TerminalRendererFailed -> "terminal_renderer_failed"
    SessionError.NotificationPermissionRequired -> "notification_permission_required"
    SessionError.ServiceStartFailed -> "service_start_failed"
}

private fun StringBuilder.appendDiagnosticField(
    name: String,
    value: String,
    maxCharacters: Int = MAX_GENERAL_FIELD_CHARS,
) {
    append(name)
    append(": ")
    appendLine(value.toDiagnosticField(maxCharacters))
}

private fun String.toDiagnosticField(maxCharacters: Int): String = buildString {
    this@toDiagnosticField.take(maxCharacters).forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(if (character.isISOControl()) '?' else character)
        }
    }
    if (this@toDiagnosticField.length > maxCharacters) append("...[truncated]")
}

private fun formatUtc(timestampMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestampMillis))

const val MAX_SENSITIVE_COMMANDS = 20
const val MAX_REPORT_CHARS = 65_536
private const val MAX_GENERAL_FIELD_CHARS = 512
private const val MAX_COMMAND_FIELD_CHARS = 1_024
