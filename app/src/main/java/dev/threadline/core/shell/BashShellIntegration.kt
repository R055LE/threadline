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

enum class CommandExecutionMode(
    internal val shellToken: String,
) {
    PERSISTENT("persistent"),
    ISOLATED("isolated"),
}

internal fun commandMayChangePersistentStrictMode(command: String): Boolean =
    STRICT_SHELL_OPTION_LINE.containsMatchIn(command)

private val STRICT_SHELL_OPTION_LINE = Regex(
    pattern = """(?m)^[\t ]*set[\t ]+(?:-[A-Za-z]*[eu][A-Za-z]*|-o[\t ]+(?:errexit|nounset|pipefail))(?:[\t ;]|$)""",
)

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
        append("  local __tl_mode=\"\$3\"\n")
        append("  local __tl_exit\n")
        append("  local __tl_previous_int_trap\n")
        append("  __tl_previous_int_trap=\"\$(trap -p INT)\"\n")
        // Merely recording INT lets a builtin-only infinite loop resume after the handler.
        // Install before publishing start so an immediate stop cannot land in a handler gap.
        // Finish the lifecycle inside the trap, then return directly from this wrapper.
        append("  trap '\n")
        append("    __tl_exit=130\n")
        append("    if [[ -n \"\$__tl_previous_int_trap\" ]]; then\n")
        append("      builtin eval -- \"\$__tl_previous_int_trap\"\n")
        append("    else\n")
        append("      trap - INT\n")
        append("    fi\n")
        append("    printf \"\\033]777;threadline;")
        append(sessionNonce.value)
        append(";end;%s;%s;%s\\007\" \"\$__tl_id\" \"\$__tl_exit\" \"\$PWD\"\n")
        append("    if [[ \"\$__tl_mode\" == isolated ]]; then\n")
        append("      return 0\n")
        append("    fi\n")
        append("    return \"\$__tl_exit\"\n")
        append("  ' INT\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";start;%s\\007' \"\$__tl_id\"\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";output;%s\\007' \"\$__tl_id\"\n")
        append("  if [[ \"\$__tl_mode\" == isolated ]]; then\n")
        append("    if command bash --noprofile --norc -c \"\$__tl_command\"; then\n")
        append("      __tl_exit=0\n")
        append("    else\n")
        append("      __tl_exit=\$?\n")
        append("    fi\n")
        append("  else\n")
        append("    builtin eval -- \"\$__tl_command\"\n")
        append("    __tl_exit=\$?\n")
        append("  fi\n")
        append("  if [[ -n \"\$__tl_previous_int_trap\" ]]; then\n")
        append("    builtin eval -- \"\$__tl_previous_int_trap\"\n")
        append("  else\n")
        append("    trap - INT\n")
        append("  fi\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";end;%s;%s;%s\\007' \"\$__tl_id\" \"\$__tl_exit\" \"\$PWD\"\n")
        append("  if [[ \"\$__tl_mode\" == isolated ]]; then\n")
        append("    return 0\n")
        append("  fi\n")
        append("  return \"\$__tl_exit\"\n")
        append("}\n")
        append(invocationText(probeCommandId, NO_OP_COMMAND))
    }.encodeToByteArray()

    fun invocation(
        commandId: CommandId,
        command: String,
        executionMode: CommandExecutionMode = CommandExecutionMode.PERSISTENT,
    ): ByteArray = invocationText(commandId, command, executionMode).encodeToByteArray()

    private fun invocationText(
        commandId: CommandId,
        command: String,
        executionMode: CommandExecutionMode = CommandExecutionMode.PERSISTENT,
    ): String = buildString {
        append(functionName)
        append(' ')
        append(ShellWordQuoter.quote(commandId.value))
        append(' ')
        append(ShellWordQuoter.quote(command))
        append(' ')
        append(ShellWordQuoter.quote(executionMode.shellToken))
        append('\n')
    }

    private companion object {
        const val NO_OP_COMMAND = ":"
    }
}
