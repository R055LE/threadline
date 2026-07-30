package dev.threadline.core.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalBridgeInputTest {
    @Test
    fun extraKeyConsumesArmedModifiersExactlyOnce() {
        val sent = mutableListOf<ByteArray>()
        val bridge = TerminalBridge().apply {
            bind(
                onInput = sent::add,
                onResize = {},
            )
        }

        bridge.toggleControl()
        bridge.toggleAlt()
        assertEquals(
            TerminalModifiers(control = true, alt = true),
            bridge.modifiers.value,
        )

        bridge.sendKey(TerminalKey.ARROW_UP)
        bridge.sendKey(TerminalKey.TAB)

        assertArrayEquals("\u001b[1;7A".encodeToByteArray(), sent[0])
        assertArrayEquals(byteArrayOf(0x09), sent[1])
        assertEquals(TerminalModifiers(), bridge.modifiers.value)
    }

    @Test
    fun clearModifiersPreventsAStaleModifierFromChangingInput() {
        val sent = mutableListOf<ByteArray>()
        val bridge = TerminalBridge().apply {
            bind(
                onInput = sent::add,
                onResize = {},
            )
        }

        bridge.toggleAlt()
        bridge.clearModifiers()
        bridge.sendKey(TerminalKey.ESCAPE)

        assertArrayEquals(byteArrayOf(0x1b), sent.single())
        assertEquals(TerminalModifiers(), bridge.modifiers.value)
    }
}
