package dev.threadline.core.shell

object ShellWordQuoter {
    fun quote(value: String): String {
        require('\u0000' !in value) { "Shell words cannot contain NUL bytes" }
        return buildString(value.length + 2) {
            append('\'')
            value.forEach { character ->
                if (character == '\'') {
                    append("'\\''")
                } else {
                    append(character)
                }
            }
            append('\'')
        }
    }
}

/**
 * Builds the temporary Bash integration installed into one persistent PTY
 * shell. The nonce is restricted to safe identifier characters by
 * [SessionNonce], so the generated function name cannot alter shell syntax.
 */
class BashShellIntegration(
    private val sessionNonce: SessionNonce,
) {
    val functionName: String = "__threadline_run_${sessionNonce.value}"

    fun bootstrap(probeCommandId: CommandId): ByteArray = buildString {
        append(functionName)
        append("() {\n")
        append("  local __tl_id=\"\$1\"\n")
        append("  local __tl_command=\"\$2\"\n")
        append("  local __tl_exit\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";start;%s\\007' \"\$__tl_id\"\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";output;%s\\007' \"\$__tl_id\"\n")
        append("  builtin eval -- \"\$__tl_command\"\n")
        append("  __tl_exit=\$?\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";end;%s;%s;%s\\007' \"\$__tl_id\" \"\$__tl_exit\" \"\$PWD\"\n")
        append("  return \"\$__tl_exit\"\n")
        append("}\n")
        append(invocationText(probeCommandId, NO_OP_COMMAND))
    }.encodeToByteArray()

    fun invocation(
        commandId: CommandId,
        command: String,
    ): ByteArray = invocationText(commandId, command).encodeToByteArray()

    private fun invocationText(
        commandId: CommandId,
        command: String,
    ): String = buildString {
        append(functionName)
        append(' ')
        append(ShellWordQuoter.quote(commandId.value))
        append(' ')
        append(ShellWordQuoter.quote(command))
        append('\n')
    }

    private companion object {
        const val NO_OP_COMMAND = ":"
    }
}
