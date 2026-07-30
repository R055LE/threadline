package dev.threadline.core.shell

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

        assertEquals(
            "__threadline_run_${nonce.value} 'command-42' " +
                "'printf '\\''%s\n'\\'' \"\$(uname)\"'\n",
            invocation,
        )
    }

    @Test
    fun `bootstrap installs nonce-scoped function and runs no-op probe`() {
        val probeId = CommandId("bootstrap-probe")
        val bootstrap = integration.bootstrap(probeId).decodeToString()

        assertTrue(bootstrap.startsWith("__threadline_run_${nonce.value}() {\n"))
        assertTrue(
            bootstrap.contains(
                "printf '\\033]777;threadline;${nonce.value};start;%s\\007'",
            ),
        )
        assertTrue(bootstrap.contains("builtin eval -- \"\$__tl_command\""))
        assertTrue(bootstrap.contains("trap '__tl_interrupted=1' INT"))
        assertTrue(
            bootstrap.contains(
                "builtin eval -- \"\$__tl_previous_int_trap\"",
            ),
        )
        assertTrue(bootstrap.contains("if (( __tl_interrupted )); then __tl_exit=130; fi"))
        assertTrue(
            bootstrap.endsWith(
                "__threadline_run_${nonce.value} 'bootstrap-probe' ':'\n",
            ),
        )
        assertFalse(bootstrap.contains("\u001b]"))
    }

    @Test
    fun `generated command ids are protocol-safe UUIDs`() {
        val id = CommandId.random()

        assertEquals(36, id.value.length)
        assertTrue(id.value.all { it.isLetterOrDigit() || it == '-' })
    }
}
