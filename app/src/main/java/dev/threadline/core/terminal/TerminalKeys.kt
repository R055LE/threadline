package dev.threadline.core.terminal

internal enum class TerminalKey {
    ESCAPE,
    TAB,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    DELETE,
}

internal data class TerminalModifiers(
    val control: Boolean = false,
    val alt: Boolean = false,
)

internal object TerminalKeyEncoder {
    fun keyboardInput(
        bytes: ByteArray,
        modifiers: TerminalModifiers,
    ): ByteArray {
        val controlled = if (modifiers.control && bytes.size == 1) {
            byteArrayOf(controlCharacter(bytes.single()))
        } else {
            bytes.copyOf()
        }
        return controlled.withAltPrefix(modifiers.alt)
    }

    fun extraKey(
        key: TerminalKey,
        modifiers: TerminalModifiers,
    ): ByteArray = when (key) {
        TerminalKey.ESCAPE -> byteArrayOf(ESC).withAltPrefix(modifiers.alt)
        TerminalKey.TAB -> byteArrayOf(TAB).withAltPrefix(modifiers.alt)
        TerminalKey.ARROW_UP -> cursorKey('A', modifiers)
        TerminalKey.ARROW_DOWN -> cursorKey('B', modifiers)
        TerminalKey.ARROW_RIGHT -> cursorKey('C', modifiers)
        TerminalKey.ARROW_LEFT -> cursorKey('D', modifiers)
        TerminalKey.HOME -> cursorKey('H', modifiers)
        TerminalKey.END -> cursorKey('F', modifiers)
        TerminalKey.PAGE_UP -> tildeKey(5, modifiers)
        TerminalKey.PAGE_DOWN -> tildeKey(6, modifiers)
        TerminalKey.DELETE -> tildeKey(3, modifiers)
    }

    private fun cursorKey(
        final: Char,
        modifiers: TerminalModifiers,
    ): ByteArray {
        val modifier = modifiers.xtermParameter()
        return if (modifier == null) {
            "$CSI$final".encodeToByteArray()
        } else {
            "${CSI}1;$modifier$final".encodeToByteArray()
        }
    }

    private fun tildeKey(
        number: Int,
        modifiers: TerminalModifiers,
    ): ByteArray {
        val modifier = modifiers.xtermParameter()
        return if (modifier == null) {
            "$CSI$number~".encodeToByteArray()
        } else {
            "$CSI$number;$modifier~".encodeToByteArray()
        }
    }

    private fun TerminalModifiers.xtermParameter(): Int? = when {
        control && alt -> 7
        control -> 5
        alt -> 3
        else -> null
    }

    private fun ByteArray.withAltPrefix(alt: Boolean): ByteArray =
        if (alt) byteArrayOf(ESC) + this else this

    private fun controlCharacter(byte: Byte): Byte {
        val value = byte.toInt() and 0xff
        val controlled = when (value) {
            in 'a'.code..'z'.code -> value - 'a'.code + 1
            in '@'.code..'_'.code -> value and CONTROL_MASK
            ' '.code, '2'.code -> 0
            '3'.code -> ESC.toInt()
            '4'.code -> 0x1c
            '5'.code -> 0x1d
            '6'.code -> 0x1e
            '7'.code, '/'.code -> 0x1f
            '8'.code, '?'.code -> 0x7f
            else -> value
        }
        return controlled.toByte()
    }

    private const val CSI = "\u001b["
    private const val CONTROL_MASK = 0x1f
    private const val ESC: Byte = 0x1b
    private const val TAB: Byte = 0x09
}
