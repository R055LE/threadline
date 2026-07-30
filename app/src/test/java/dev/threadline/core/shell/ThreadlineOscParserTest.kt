package dev.threadline.core.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ThreadlineOscParserTest {
    private val nonce = SessionNonce("0123456789abcdef0123456789abcdef")
    private val commandId = CommandId("command-42")

    @Test
    fun `marker is recognized at every possible buffer split`() {
        val marker = marker("start", commandId.value)

        for (split in 1 until marker.size) {
            val parser = ThreadlineOscParser(nonce)
            val first = parser.consume(marker.copyOfRange(0, split))
            val second = parser.consume(marker.copyOfRange(split, marker.size))

            assertArrayEquals("split $split first output", byteArrayOf(), first.transcriptBytes)
            assertEquals("split $split first events", emptyList<Any>(), first.events)
            assertArrayEquals("split $split second output", byteArrayOf(), second.transcriptBytes)
            assertEquals(
                "split $split second events",
                listOf(ShellLifecycleEvent.CommandStarted(commandId)),
                second.events,
            )
        }
    }

    @Test
    fun `multiple markers and ordinary output retain event and byte order`() {
        val bytes = buildList {
            add("before".encodeToByteArray())
            add(marker("start", commandId.value))
            add("middle".encodeToByteArray())
            add(marker("output", commandId.value))
            add("after".encodeToByteArray())
            add(marker("end", commandId.value, "17", "/srv/app;blue"))
        }.flattenBytes()

        val result = ThreadlineOscParser(nonce).consume(bytes)

        assertArrayEquals("beforemiddleafter".encodeToByteArray(), result.transcriptBytes)
        assertEquals(
            listOf(
                ShellLifecycleEvent.CommandStarted(commandId),
                ShellLifecycleEvent.CommandOutputStarted(commandId),
                ShellLifecycleEvent.CommandEnded(commandId, 17, "/srv/app;blue"),
            ),
            result.events,
        )
        assertOrderedItems(
            result,
            "before",
            ShellLifecycleEvent.CommandStarted(commandId),
            "middle",
            ShellLifecycleEvent.CommandOutputStarted(commandId),
            "after",
            ShellLifecycleEvent.CommandEnded(commandId, 17, "/srv/app;blue"),
        )
    }

    @Test
    fun `partial trailing marker is retained until completed`() {
        val parser = ThreadlineOscParser(nonce)
        val marker = marker("output", commandId.value)
        val split = marker.size - 1

        val partial = parser.consume(
            "visible".encodeToByteArray() + marker.copyOfRange(0, split),
        )
        val completed = parser.consume(marker.copyOfRange(split, marker.size))

        assertArrayEquals("visible".encodeToByteArray(), partial.transcriptBytes)
        assertTrue(partial.events.isEmpty())
        assertArrayEquals(byteArrayOf(), completed.transcriptBytes)
        assertEquals(
            listOf(ShellLifecycleEvent.CommandOutputStarted(commandId)),
            completed.events,
        )
    }

    @Test
    fun `unfinished marker passes through on finish`() {
        val parser = ThreadlineOscParser(nonce)
        val partial = marker("start", commandId.value).dropLast(1).toByteArray()

        assertArrayEquals(byteArrayOf(), parser.consume(partial).transcriptBytes)
        assertArrayEquals(partial, parser.finish())
    }

    @Test
    fun `invalid nonce passes through unchanged`() {
        val sequence = marker(
            event = "start",
            commandId = commandId.value,
            markerNonce = "fedcba9876543210fedcba9876543210",
        )

        val result = ThreadlineOscParser(nonce).consume(sequence)

        assertArrayEquals(sequence, result.transcriptBytes)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `oversized marker passes through and scanning resumes`() {
        val oversized = osc(
            "777;threadline;${nonce.value};start;${"x".repeat(100)}",
        )
        val recognized = marker("start", commandId.value)
        val parser = ThreadlineOscParser(nonce, maximumSequenceBytes = 96)

        val result = parser.consume(oversized + recognized)

        assertArrayEquals(oversized, result.transcriptBytes)
        assertEquals(
            listOf(ShellLifecycleEvent.CommandStarted(commandId)),
            result.events,
        )
    }

    @Test
    fun `default sequence limit bounds a huge current directory marker`() {
        val oversized = marker(
            "end",
            commandId.value,
            "0",
            "/${"directory".repeat(1_100)}",
        )

        val result = ThreadlineOscParser(nonce).consume(oversized)

        assertTrue(oversized.size > 8_192)
        assertArrayEquals(oversized, result.transcriptBytes)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `unknown OSC sequence passes through unchanged`() {
        val sequence = osc("0;remote title")

        val result = ThreadlineOscParser(nonce).consume(sequence)

        assertArrayEquals(sequence, result.transcriptBytes)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `UTF-8 split across buffers passes through byte for byte`() {
        val unicode = "start λ 🧵 end".encodeToByteArray()
        val split = unicode.indexOfFirst { it.toInt() and 0xc0 == 0x80 }
        val parser = ThreadlineOscParser(nonce)

        val first = parser.consume(unicode.copyOfRange(0, split))
        val second = parser.consume(unicode.copyOfRange(split, unicode.size))

        assertArrayEquals(unicode.copyOfRange(0, split), first.transcriptBytes)
        assertArrayEquals(unicode.copyOfRange(split, unicode.size), second.transcriptBytes)
        assertEquals("start λ 🧵 end", (first.transcriptBytes + second.transcriptBytes).decodeToString())
    }

    @Test
    fun `carriage return progress output passes through unchanged`() {
        val progress = "0%\r25%\r100%\r\n".encodeToByteArray()

        val result = ThreadlineOscParser(nonce).consume(progress)

        assertArrayEquals(progress, result.transcriptBytes)
    }

    @Test
    fun `ANSI styles pass through unchanged`() {
        val styled = "\u001b[31mred\u001b[0m".encodeToByteArray()

        val result = ThreadlineOscParser(nonce).consume(styled)

        assertArrayEquals(styled, result.transcriptBytes)
    }

    @Test
    fun `high-volume output remains exact around fragmented markers`() {
        val before = ByteArray(100_000) { ('a'.code + it % 26).toByte() }
        val after = ByteArray(100_000) { ('A'.code + it % 26).toByte() }
        val marker = marker("output", commandId.value)
        val stream = before + marker + after
        val parser = ThreadlineOscParser(nonce)
        val transcript = mutableListOf<ByteArray>()
        val events = mutableListOf<ShellLifecycleEvent>()

        stream.asList().chunked(257).forEach { chunk ->
            val result = parser.consume(chunk.toByteArray())
            transcript += result.transcriptBytes
            events += result.events
        }

        assertArrayEquals(before + after, transcript.flattenBytes())
        assertEquals(
            listOf(ShellLifecycleEvent.CommandOutputStarted(commandId)),
            events,
        )
    }

    @Test
    fun `string terminator form is recognized`() {
        val sequence = osc(
            payload = "777;threadline;${nonce.value};end;${commandId.value};0;/home/threadline",
            terminator = "\u001b\\",
        )

        val result = ThreadlineOscParser(nonce).consume(sequence)

        assertArrayEquals(byteArrayOf(), result.transcriptBytes)
        assertEquals(
            listOf(
                ShellLifecycleEvent.CommandEnded(
                    commandId = commandId,
                    exitStatus = 0,
                    currentDirectory = "/home/threadline",
                ),
            ),
            result.events,
        )
    }

    @Test
    fun `malformed known marker passes through unchanged`() {
        val invalidExit = marker("end", commandId.value, "not-an-exit", "/tmp")

        val result = ThreadlineOscParser(nonce).consume(invalidExit)

        assertArrayEquals(invalidExit, result.transcriptBytes)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `generated nonces use independent 128-bit hex values`() {
        val first = SessionNonce.random(SecureRandom())
        val second = SessionNonce.random(SecureRandom())

        assertEquals(32, first.value.length)
        assertTrue(first.value.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, second)
    }

    private fun marker(
        event: String,
        commandId: String,
        vararg remainingFields: String,
        markerNonce: String = nonce.value,
    ): ByteArray {
        val fields = listOf("777", "threadline", markerNonce, event, commandId) +
            remainingFields
        return osc(fields.joinToString(";"))
    }

    private fun osc(
        payload: String,
        terminator: String = "\u0007",
    ): ByteArray = "\u001b]$payload$terminator".encodeToByteArray()

    private fun assertOrderedItems(
        result: ProtocolScanResult,
        vararg expected: Any,
    ) {
        assertEquals(expected.size, result.items.size)
        expected.zip(result.items).forEachIndexed { index, (expectedItem, actualItem) ->
            when (expectedItem) {
                is String -> {
                    assertTrue("item $index is transcript bytes", actualItem is ProtocolStreamItem.TranscriptBytes)
                    assertArrayEquals(
                        "item $index transcript bytes",
                        expectedItem.encodeToByteArray(),
                        (actualItem as ProtocolStreamItem.TranscriptBytes).bytes,
                    )
                }

                is ShellLifecycleEvent ->
                    assertEquals(
                        "item $index lifecycle event",
                        ProtocolStreamItem.Lifecycle(expectedItem),
                        actualItem,
                    )

                else -> error("Unsupported expected item: $expectedItem")
            }
        }
    }
}

private fun Iterable<ByteArray>.flattenBytes(): ByteArray =
    fold(ByteArray(0)) { accumulated, bytes -> accumulated + bytes }
