package dev.threadline.core.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveTerminalHintDetectorTest {
    @Test
    fun `alternate-screen hint survives every input split`() {
        val encoded = "before\u001b[?1049hafter".encodeToByteArray()

        for (split in 1 until encoded.size) {
            val collector = TranscriptCollector()
            collector.consume(encoded.copyOfRange(0, split))
            collector.consume(encoded.copyOfRange(split, encoded.size))
            collector.finish()
            val output = collector.snapshot()

            assertEquals(
                "split $split",
                InteractiveTerminalHint.ALTERNATE_SCREEN,
                output.interactiveHint,
            )
            assertTrue("split $split", output.approximate)
        }
    }

    @Test
    fun `strong interactive control sequences produce typed hints`() {
        val cases = mapOf(
            "\u001b[12;40H" to InteractiveTerminalHint.CURSOR_ADDRESSING,
            "\u001b[?1000;1006h" to InteractiveTerminalHint.MOUSE_TRACKING,
            "\u001b[?2004h" to InteractiveTerminalHint.BRACKETED_PASTE,
        )

        cases.forEach { (sequence, expected) ->
            val collector = TranscriptCollector()
            collector.consume(sequence.encodeToByteArray())
            collector.finish()

            assertEquals(sequence, expected, collector.snapshot().interactiveHint)
        }
    }

    @Test
    fun `ordinary styles and private-mode disablement do not suggest raw mode`() {
        val collector = TranscriptCollector()

        collector.consume("\u001b[31mred\u001b[0m\u001b[?1049l".encodeToByteArray())
        collector.finish()

        assertNull(collector.snapshot().interactiveHint)
    }
}
