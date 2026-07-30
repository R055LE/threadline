package dev.threadline.core.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCollectorTest {
    @Test
    fun `UTF-8 decoding survives every buffer split`() {
        val encoded = "start λ 🧵 end".encodeToByteArray()

        for (split in 1 until encoded.size) {
            val collector = TranscriptCollector()
            collector.consume(encoded.copyOfRange(0, split))
            collector.consume(encoded.copyOfRange(split, encoded.size))
            collector.finish()

            assertEquals("split $split", "start λ 🧵 end", collector.snapshot().plainText)
        }
    }

    @Test
    fun `line controls backspace and tabs update display text`() {
        val collector = TranscriptCollector(tabWidth = 8)

        collector.consume("abc\bX\tZ\rshort\r\nnext".encodeToByteArray())
        collector.finish()

        assertEquals("short\nnext", collector.snapshot().plainText)
    }

    @Test
    fun `repeated carriage returns replace the progress line`() {
        val collector = TranscriptCollector()

        collector.consume("0%\r25%\r100%\r\n".encodeToByteArray())
        collector.finish()

        assertEquals("100%\n", collector.snapshot().plainText)
    }

    @Test
    fun `ANSI SGR styles survive every escape-sequence split`() {
        val encoded = "plain \u001b[1;31mred\u001b[0m normal".encodeToByteArray()
        val expectedStyle = TranscriptStyle(
            foreground = AnsiColor.Indexed(1),
            bold = true,
        )

        for (split in 1 until encoded.size) {
            val collector = TranscriptCollector()
            collector.consume(encoded.copyOfRange(0, split))
            collector.consume(encoded.copyOfRange(split, encoded.size))
            collector.finish()
            val output = collector.snapshot()

            assertEquals("split $split text", "plain red normal", output.plainText)
            assertEquals(
                "split $split runs",
                listOf(StyledRun(6, 9, expectedStyle)),
                output.styledRuns,
            )
            assertFalse("split $split approximate", output.approximate)
        }
    }

    @Test
    fun `indexed and truecolor SGR values are represented`() {
        val collector = TranscriptCollector()

        collector.consume(
            (
                "\u001b[38;5;200mI\u001b[48;2;1;2;3mR" +
                    "\u001b[39;49mD"
                ).encodeToByteArray(),
        )
        collector.finish()

        assertEquals(
            listOf(
                StyledRun(
                    0,
                    1,
                    TranscriptStyle(foreground = AnsiColor.Indexed(200)),
                ),
                StyledRun(
                    1,
                    2,
                    TranscriptStyle(
                        foreground = AnsiColor.Indexed(200),
                        background = AnsiColor.Rgb(1, 2, 3),
                    ),
                ),
            ),
            collector.snapshot().styledRuns,
        )
    }

    @Test
    fun `unsupported terminal operations are hidden and marked approximate`() {
        val collector = TranscriptCollector()

        collector.consume(
            (
                "before\u001b[2Jafter" +
                    "\u001b]0;remote title\u0007done"
                ).encodeToByteArray(),
        )
        collector.finish()
        val output = collector.snapshot()

        assertEquals("beforeafterdone", output.plainText)
        assertTrue(output.approximate)
    }

    @Test
    fun `rendered output keeps a bounded tail and counts every input byte`() {
        val collector = TranscriptCollector(maximumRenderedCharacters = 5)

        collector.consume("abcdef".encodeToByteArray())
        collector.finish()
        val output = collector.snapshot()

        assertEquals("bcdef", output.plainText)
        assertEquals(6, output.byteCount)
        assertTrue(output.truncated)
    }
}
