package dev.threadline.core.diagnostics

import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import dev.threadline.core.shell.CommandId
import dev.threadline.core.shell.StructuredShellState
import dev.threadline.core.transcript.CommandOutput
import dev.threadline.core.transcript.CommandStatus
import dev.threadline.core.transcript.CommandTranscriptState
import dev.threadline.core.transcript.CommandTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun defaultReportRedactsSessionAndTranscriptContent() {
        val endpoint = HostEndpoint("secret.example", 2222)
        val transcript = transcript(
            command = "cat /secret/command.txt",
            directory = "/secret/directory",
            output = "secret remote output",
        )
        val snapshot = diagnosticSessionSnapshot(
            connection = SessionState.Failed(
                SessionError.HostKeyChanged(
                    endpoint = endpoint,
                    previousFingerprint = "old-secret-fingerprint",
                    presentedFingerprint = "new-secret-fingerprint",
                ),
            ),
            structuredShell = StructuredShellState.Ready("/secret/current-directory"),
            transcript = transcript,
            transcriptSaveFailed = true,
        )

        val report = generateDiagnosticReport(input(session = snapshot))

        assertTrue(report.contains("error_code: host_key_changed"))
        assertTrue(report.contains("status_succeeded: 1"))
        assertTrue(report.contains("retained_output_characters: 20"))
        assertTrue(report.contains("host_profile: redacted"))
        assertTrue(report.contains("command_content: redacted"))
        assertTrue(report.contains("command_output: never_included"))
        listOf(
            "secret.example",
            "old-secret-fingerprint",
            "new-secret-fingerprint",
            "cat /secret/command.txt",
            "/secret/directory",
            "/secret/current-directory",
            "secret remote output",
        ).forEach { secret -> assertFalse("Report leaked $secret", report.contains(secret)) }
    }

    @Test
    fun explicitOptInIncludesBoundedSessionFieldsAndCommandsButNotOutput() {
        val snapshot = diagnosticSessionSnapshot(
            connection = SessionState.Connected("Private display", dev.threadline.core.model.TerminalSize(24, 80)),
            structuredShell = StructuredShellState.Ready("/private/current"),
            transcript = transcript(output = "output-must-stay-private"),
            transcriptSaveFailed = false,
        )
        val report = generateDiagnosticReport(
            input(
                session = snapshot,
                sensitiveDetails = SensitiveDiagnosticDetails(
                    displayName = "Private display",
                    hostname = "host.private.example",
                    port = 2200,
                    username = "private-user",
                    currentDirectory = "/private/current",
                    recentCommands = listOf(
                        SensitiveDiagnosticCommand(
                            status = CommandStatus.SUCCEEDED,
                            directory = "/private/start",
                            command = "printf 'command-private'\nnext-line",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(report.contains("sensitive_session_details_included: true"))
        assertTrue(report.contains("hostname: host.private.example"))
        assertTrue(report.contains("username: private-user"))
        assertTrue(report.contains("command_1_text: printf 'command-private'\\nnext-line"))
        assertTrue(report.contains("command_output: never_included"))
        assertFalse(report.contains("output-must-stay-private"))
    }

    @Test
    fun sensitiveCommandCountAndReportSizeAreBounded() {
        val commands = (1..100).map { index ->
            SensitiveDiagnosticCommand(
                status = CommandStatus.SUCCEEDED,
                directory = "/directory/$index",
                command = "$index-${"x".repeat(10_000)}",
            )
        }
        val report = generateDiagnosticReport(
            input(
                sensitiveDetails = SensitiveDiagnosticDetails(
                    displayName = "display",
                    hostname = "host",
                    port = 22,
                    username = "user",
                    currentDirectory = null,
                    recentCommands = commands,
                ),
            ),
        )

        assertTrue(report.length <= MAX_REPORT_CHARS)
        assertTrue(report.contains("recent_command_count: $MAX_SENSITIVE_COMMANDS"))
        assertFalse(report.contains("command_21_status"))
        assertTrue(report.contains("...[truncated]"))
    }

    @Test
    fun everySessionErrorUsesAStableSanitizedCode() {
        val endpoint = HostEndpoint("private-host", 22)
        val errors = listOf(
            SessionError.HostKeyRejected("private-fingerprint") to "host_key_rejected",
            SessionError.HostKeyChanged(endpoint, "old", "new") to "host_key_changed",
            SessionError.KnownHostStorageFailed to "known_host_storage_failed",
            SessionError.AuthenticationRejected to "authentication_rejected",
            SessionError.UnsupportedPrivateKey to "unsupported_private_key",
            SessionError.PtyRejected to "pty_rejected",
            SessionError.ShellRejected to "shell_rejected",
            SessionError.PtyResizeRejected to "pty_resize_rejected",
            SessionError.ConnectionFailed to "connection_failed",
            SessionError.DnsResolutionFailed to "dns_resolution_failed",
            SessionError.ConnectionTimedOut to "connection_timed_out",
            SessionError.ConnectionRefused to "connection_refused",
            SessionError.NetworkUnreachable to "network_unreachable",
            SessionError.ProtocolMismatch to "protocol_mismatch",
            SessionError.ConnectionLost to "connection_lost",
            SessionError.InputBackpressure to "input_backpressure",
            SessionError.TerminalRendererFailed to "terminal_renderer_failed",
            SessionError.NotificationPermissionRequired to "notification_permission_required",
            SessionError.ServiceStartFailed to "service_start_failed",
        )

        errors.forEach { (error, expectedCode) ->
            val snapshot = diagnosticSessionSnapshot(
                connection = SessionState.Failed(error),
                structuredShell = StructuredShellState.Inactive,
                transcript = CommandTranscriptState(),
                transcriptSaveFailed = false,
            )
            assertEquals(expectedCode, snapshot.errorCode)
        }
    }

    private fun input(
        session: DiagnosticSessionSnapshot = diagnosticSessionSnapshot(
            connection = SessionState.Disconnected,
            structuredShell = StructuredShellState.Inactive,
            transcript = CommandTranscriptState(),
            transcriptSaveFailed = false,
        ),
        sensitiveDetails: SensitiveDiagnosticDetails? = null,
    ) = DiagnosticReportInput(
        generatedAtMillis = 0,
        environment = DiagnosticEnvironment(
            appVersionName = "0.0.1",
            appVersionCode = 1,
            androidSdk = 35,
            deviceManufacturer = "Test manufacturer",
            deviceModel = "Test model",
        ),
        inventory = DiagnosticInventory(
            savedProfiles = 2,
            trustedServers = 3,
            importedPrivateKeys = 4,
            savedTranscriptSessions = 5,
        ),
        session = session,
        sensitiveDetails = sensitiveDetails,
    )

    private fun transcript(
        command: String = "echo command-private",
        directory: String = "/private/start",
        output: String = "output-private",
    ) = CommandTranscriptState(
        turns = listOf(
            CommandTurn(
                id = CommandId("command-1"),
                command = command,
                directoryAtStart = directory,
                submittedAtMillis = 1,
                startedAtMillis = 2,
                completedAtMillis = 3,
                status = CommandStatus.SUCCEEDED,
                exitStatus = 0,
                currentDirectory = "/private/end",
                output = CommandOutput(
                    plainText = output,
                    byteCount = output.length.toLong(),
                ),
            ),
        ),
    )
}
