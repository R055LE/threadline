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
        append("    return \"\$__tl_exit\"\n")
        append("  ' INT\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";start;%s\\007' \"\$__tl_id\"\n")
        append("  printf '\\033]777;threadline;")
        append(sessionNonce.value)
        append(";output;%s\\007' \"\$__tl_id\"\n")
        append("  builtin eval -- \"\$__tl_command\"\n")
        append("  __tl_exit=\$?\n")
        append("  if [[ -n \"\$__tl_previous_int_trap\" ]]; then\n")
        append("    builtin eval -- \"\$__tl_previous_int_trap\"\n")
        append("  else\n")
        append("    trap - INT\n")
        append("  fi\n")
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
