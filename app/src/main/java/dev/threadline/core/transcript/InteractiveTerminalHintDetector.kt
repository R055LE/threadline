package dev.threadline.core.transcript

internal object InteractiveTerminalHintDetector {
    private val alternateScreenModes = setOf(47, 1_047, 1_049)
    private val mouseTrackingModes = setOf(1_000, 1_002, 1_003, 1_005, 1_006, 1_015)
    private const val BRACKETED_PASTE_MODE = 2_004

    fun detectCsi(sequence: ByteArray): InteractiveTerminalHint? {
        if (
            sequence.size < MINIMUM_CSI_BYTES ||
            sequence[0] != ESC ||
            sequence[1] != CSI_INTRODUCER
        ) {
            return null
        }

        val final = sequence.last().toInt().toChar()
        if (final == 'H' || final == 'f') {
            return InteractiveTerminalHint.CURSOR_ADDRESSING
        }
        if (final != 'h' || sequence[2] != PRIVATE_MODE_INTRODUCER) {
            return null
        }

        val modes = sequence
            .copyOfRange(3, sequence.lastIndex)
            .decodeToString()
            .split(';')
            .mapNotNull(String::toIntOrNull)
            .toSet()
        return when {
            modes.any(alternateScreenModes::contains) ->
                InteractiveTerminalHint.ALTERNATE_SCREEN

            modes.any(mouseTrackingModes::contains) ->
                InteractiveTerminalHint.MOUSE_TRACKING

            BRACKETED_PASTE_MODE in modes ->
                InteractiveTerminalHint.BRACKETED_PASTE

            else -> null
        }
    }

    private const val MINIMUM_CSI_BYTES = 3
    private const val ESC: Byte = 0x1b
    private const val CSI_INTRODUCER: Byte = 0x5b
    private const val PRIVATE_MODE_INTRODUCER: Byte = 0x3f
}
