package dev.threadline.core.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalKeyEncoderTest {
    @Test
    fun `control and alt modify the next single keyboard character`() {
        assertBytes(
            byteArrayOf(0x03),
            TerminalKeyEncoder.keyboardInput(
                "c".encodeToByteArray(),
                TerminalModifiers(control = true),
            ),
        )
        assertBytes(
            byteArrayOf(0x1b, 0x03),
            TerminalKeyEncoder.keyboardInput(
                "C".encodeToByteArray(),
                TerminalModifiers(control = true, alt = true),
            ),
        )
        assertBytes(
            byteArrayOf(0x7f),
            TerminalKeyEncoder.keyboardInput(
                "?".encodeToByteArray(),
                TerminalModifiers(control = true),
            ),
        )
    }

    @Test
    fun `control does not corrupt multi-byte keyboard callbacks`() {
        assertBytes(
            "\u001bpaste".encodeToByteArray(),
            TerminalKeyEncoder.keyboardInput(
                "paste".encodeToByteArray(),
                TerminalModifiers(control = true, alt = true),
            ),
        )
    }

    @Test
    fun `navigation keys use xterm modifier parameters`() {
        assertBytes(
            "\u001b[A".encodeToByteArray(),
            TerminalKeyEncoder.extraKey(TerminalKey.ARROW_UP, TerminalModifiers()),
        )
        assertBytes(
            "\u001b[1;5D".encodeToByteArray(),
            TerminalKeyEncoder.extraKey(
                TerminalKey.ARROW_LEFT,
                TerminalModifiers(control = true),
            ),
        )
        assertBytes(
            "\u001b[5;3~".encodeToByteArray(),
            TerminalKeyEncoder.extraKey(
                TerminalKey.PAGE_UP,
                TerminalModifiers(alt = true),
            ),
        )
        assertBytes(
            "\u001b[3;7~".encodeToByteArray(),
            TerminalKeyEncoder.extraKey(
                TerminalKey.DELETE,
                TerminalModifiers(control = true, alt = true),
            ),
        )
    }

    @Test
    fun `escape and tab emit direct control bytes`() {
        assertBytes(
            byteArrayOf(0x1b),
            TerminalKeyEncoder.extraKey(TerminalKey.ESCAPE, TerminalModifiers()),
        )
        assertBytes(
            byteArrayOf(0x1b, 0x09),
            TerminalKeyEncoder.extraKey(
                TerminalKey.TAB,
                TerminalModifiers(alt = true),
            ),
        )
    }

    private fun assertBytes(expected: ByteArray, actual: ByteArray) {
        assertArrayEquals(expected, actual)
    }
}
