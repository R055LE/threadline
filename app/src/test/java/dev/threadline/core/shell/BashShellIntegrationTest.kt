package dev.threadline.core.shell

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BashShellIntegrationTest {
    private val nonce = SessionNonce("0123456789abcdef0123456789abcdef")
    private val integration = BashShellIntegration(nonce)

    @Test
    fun `empty command is one empty shell word`() {
        assertEquals("''", ShellWordQuoter.quote(""))
    }

    @Test
    fun `single quotes are closed escaped and reopened`() {
        assertEquals(
            "'printf '\\''one two'\\'''",
            ShellWordQuoter.quote("printf 'one two'"),
        )
    }

    @Test
    fun `double quotes remain literal inside the shell word`() {
        assertEquals(
            "'printf \"%s\" \"hello world\"'",
            ShellWordQuoter.quote("printf \"%s\" \"hello world\""),
        )
    }

    @Test
    fun `backslashes remain literal inside the shell word`() {
        assertEquals(
            "'printf C:\\temp\\file'",
            ShellWordQuoter.quote("printf C:\\temp\\file"),
        )
    }

    @Test
    fun `newlines remain literal inside the shell word`() {
        assertEquals(
            "'printf first\nprintf second'",
            ShellWordQuoter.quote("printf first\nprintf second"),
        )
    }

    @Test
    fun `Unicode remains literal inside the shell word`() {
        assertEquals(
            "'printf λ-🧵'",
            ShellWordQuoter.quote("printf λ-🧵"),
        )
    }

    @Test
    fun `pipes remain inert while constructing the shell word`() {
        assertEquals(
            "'printf left | sed s,l,r,'",
            ShellWordQuoter.quote("printf left | sed s,l,r,"),
        )
    }

    @Test
    fun `redirects remain inert while constructing the shell word`() {
        assertEquals(
            "'printf data > output.txt 2>&1'",
            ShellWordQuoter.quote("printf data > output.txt 2>&1"),
        )
    }

    @Test
    fun `command substitutions remain inert while constructing the shell word`() {
        assertEquals(
            "'printf \"$(uname)\"'",
            ShellWordQuoter.quote("printf \"$(uname)\""),
        )
    }

    @Test
    fun `here-document remains one multiline shell word`() {
        val command = "cat <<'EOF'\nhello\nEOF"

        assertEquals(
            "'cat <<'\\''EOF'\\''\nhello\nEOF'",
            ShellWordQuoter.quote(command),
        )
    }

    @Test
    fun `leading and trailing whitespace is preserved`() {
        assertEquals(
            "'  printf padded  \t'",
            ShellWordQuoter.quote("  printf padded  \t"),
        )
    }

    @Test
    fun `NUL is rejected because shells cannot carry it in an argument`() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellWordQuoter.quote("before\u0000after")
        }
    }

    @Test
    fun `invocation quotes both command id and user command`() {
        val invocation = integration.invocation(
            commandId = CommandId("command-42"),
            command = "printf '%s\n' \"$(uname)\"",
        ).decodeToString()

        assertTrue(invocation.startsWith("__threadline_run_${nonce.value} 'command-42' $'"))
        assertTrue(invocation.endsWith(" 'persistent'\n"))
        assertEquals(1, invocation.count { it == '\n' })
    }

    @Test
    fun `single-line quoting reconstructs multiline Unicode and shell syntax`() {
        val value = "  printf 'λ-🧵'\ncat <<'EOF'\n$(uname) | \\path\nEOF\t  "
        val process = ProcessBuilder(
            "bash",
            "--noprofile",
            "--norc",
            "-c",
            "value=${BashAnsiCWordQuoter.quote(value)}; printf '%s' \"\$value\"",
        ).start()

        assertTrue("Bash did not exit", process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(process.errorStream.readBytes().decodeToString(), 0, process.exitValue())
        assertEquals(value, process.inputStream.readBytes().decodeToString())
    }

    @Test
    fun `isolated strict command cannot terminate the integration shell`() {
        val persistentStrictId = CommandId("persistent-strict-command")
        val strictId = CommandId("strict-command")
        val nextId = CommandId("next-command")
        val process = ProcessBuilder("bash", "--noprofile", "--norc")
            .redirectErrorStream(true)
            .start()

        try {
            process.outputStream.use { input ->
                input.write(integration.bootstrap(CommandId("bootstrap-probe")))
                input.write(
                    integration.invocation(
                        commandId = persistentStrictId,
                        command = "set -euo pipefail",
                    ),
                )
                input.write(
                    integration.invocation(
                        commandId = strictId,
                        command = "set -euo pipefail; false; printf unreachable",
                        executionMode = CommandExecutionMode.ISOLATED,
                    ),
                )
                input.write(
                    integration.invocation(
                        commandId = nextId,
                        command = "printf 'next-command-ran\\n'",
                    ),
                )
            }

            assertTrue("Bash did not exit", process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.readBytes().decodeToString()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output.contains(";end;${persistentStrictId.value};0;"))
            assertTrue(output.contains(";end;${strictId.value};1;"))
            assertTrue(output.contains("next-command-ran"))
            assertTrue(output.contains(";end;${nextId.value};0;"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `bootstrap is one physical input line`() {
        val probeId = CommandId("bootstrap-probe")
        val bootstrap = integration.bootstrap(probeId).decodeToString()

        assertTrue(bootstrap.startsWith("builtin eval -- $'"))
        assertEquals(1, bootstrap.count { it == '\n' })
        assertTrue("Bootstrap must fit one canonical PTY line", bootstrap.encodeToByteArray().size < 4096)
        assertFalse(bootstrap.contains("\u001b]"))
    }

    @Test
    fun `generated command ids are protocol-safe UUIDs`() {
        val id = CommandId.random()

        assertEquals(36, id.value.length)
        assertTrue(id.value.all { it.isLetterOrDigit() || it == '-' })
    }

    @Test
    fun `strict shell option warning recognizes common prologues`() {
        assertTrue(commandMayChangePersistentStrictMode("set -euo pipefail\nprintf ready"))
        assertTrue(commandMayChangePersistentStrictMode("  set -o errexit"))
        assertTrue(commandMayChangePersistentStrictMode("set -o nounset"))
        assertTrue(commandMayChangePersistentStrictMode("set -o pipefail"))
        assertFalse(commandMayChangePersistentStrictMode("printf 'set -e'"))
        assertFalse(commandMayChangePersistentStrictMode("set +e"))
    }
}
