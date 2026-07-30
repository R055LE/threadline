package dev.threadline.core.shell

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

@JvmInline
value class SessionNonce(val value: String) {
    init {
        require(value.length in MIN_LENGTH..MAX_LENGTH)
        require(value.all(::isProtocolTokenCharacter))
    }

    companion object {
        private const val MIN_LENGTH = 16
        private const val MAX_LENGTH = 128
        private const val GENERATED_BYTES = 16
        private val hexDigits = "0123456789abcdef".toCharArray()

        fun random(random: SecureRandom = SecureRandom()): SessionNonce {
            val bytes = ByteArray(GENERATED_BYTES).also(random::nextBytes)
            val encoded = CharArray(bytes.size * 2)
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                encoded[index * 2] = hexDigits[value ushr 4]
                encoded[index * 2 + 1] = hexDigits[value and 0x0f]
            }
            bytes.fill(0)
            return SessionNonce(encoded.concatToString())
        }
    }
}

@JvmInline
value class CommandId(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH)
        require(value.all(::isProtocolTokenCharacter))
    }

    companion object {
        private const val MAX_LENGTH = 128

        fun random(): CommandId = CommandId(UUID.randomUUID().toString())
    }
}

sealed interface ShellLifecycleEvent {
    val commandId: CommandId

    data class CommandStarted(
        override val commandId: CommandId,
    ) : ShellLifecycleEvent

    data class CommandOutputStarted(
        override val commandId: CommandId,
    ) : ShellLifecycleEvent

    data class CommandEnded(
        override val commandId: CommandId,
        val exitStatus: Int,
        val currentDirectory: String,
    ) : ShellLifecycleEvent
}

sealed interface ProtocolStreamItem {
    data class TranscriptBytes(
        val bytes: ByteArray,
    ) : ProtocolStreamItem

    data class Lifecycle(
        val event: ShellLifecycleEvent,
    ) : ProtocolStreamItem
}

class ProtocolScanResult(
    val items: List<ProtocolStreamItem>,
) {
    val transcriptBytes: ByteArray
        get() {
            val transcript = ByteArrayOutputStream()
            items.filterIsInstance<ProtocolStreamItem.TranscriptBytes>()
                .forEach { transcript.write(it.bytes) }
            return transcript.toByteArray()
        }

    val events: List<ShellLifecycleEvent>
        get() = items.filterIsInstance<ProtocolStreamItem.Lifecycle>().map { it.event }
}

/**
 * Removes valid Threadline lifecycle markers from transcript bytes while leaving
 * every unrecognized sequence untouched. Callers must send the original input
 * bytes, rather than [ProtocolScanResult.transcriptBytes], to the raw terminal.
 */
class ThreadlineOscParser(
    private val sessionNonce: SessionNonce,
    private val maximumSequenceBytes: Int = DEFAULT_MAXIMUM_SEQUENCE_BYTES,
) {
    private val candidate = ByteArrayOutputStream()
    private var state = State.TEXT

    init {
        require(maximumSequenceBytes >= MINIMUM_SEQUENCE_BYTES)
    }

    fun consume(bytes: ByteArray): ProtocolScanResult {
        val transcript = ByteArrayOutputStream(bytes.size)
        val items = mutableListOf<ProtocolStreamItem>()

        bytes.forEach { byte ->
            when (state) {
                State.TEXT -> consumeTextByte(byte, transcript)
                State.ESCAPE -> consumeEscapeByte(byte, transcript)
                State.OSC -> consumeOscByte(byte, transcript, items)
                State.OSC_ESCAPE -> consumeOscEscapeByte(byte, transcript, items)
            }
        }
        appendTranscriptItem(transcript, items)

        return ProtocolScanResult(items)
    }

    /**
     * Releases an incomplete trailing escape or OSC sequence when the stream
     * ends. A partial sequence is never treated as a protocol event.
     */
    fun finish(): ByteArray {
        val trailing = candidate.toByteArray()
        resetCandidate()
        return trailing
    }

    private fun consumeTextByte(
        byte: Byte,
        transcript: ByteArrayOutputStream,
    ) {
        if (byte == ESC) {
            candidate.write(byte.toInt())
            state = State.ESCAPE
        } else {
            transcript.write(byte.toInt())
        }
    }

    private fun consumeEscapeByte(
        byte: Byte,
        transcript: ByteArrayOutputStream,
    ) {
        if (byte == OSC_INTRODUCER) {
            candidate.write(byte.toInt())
            state = State.OSC
            return
        }

        transcript.write(candidate.toByteArray())
        candidate.reset()
        if (byte == ESC) {
            candidate.write(byte.toInt())
        } else {
            transcript.write(byte.toInt())
            state = State.TEXT
        }
    }

    private fun consumeOscByte(
        byte: Byte,
        transcript: ByteArrayOutputStream,
        items: MutableList<ProtocolStreamItem>,
    ) {
        candidate.write(byte.toInt())
        if (flushIfOversized(transcript)) return
        when (byte) {
            BEL -> completeCandidate(BEL_TERMINATOR_BYTES, transcript, items)
            ESC -> state = State.OSC_ESCAPE
        }
    }

    private fun consumeOscEscapeByte(
        byte: Byte,
        transcript: ByteArrayOutputStream,
        items: MutableList<ProtocolStreamItem>,
    ) {
        candidate.write(byte.toInt())
        if (flushIfOversized(transcript)) return
        when (byte) {
            STRING_TERMINATOR ->
                completeCandidate(STRING_TERMINATOR_BYTES, transcript, items)

            ESC -> state = State.OSC_ESCAPE

            else -> state = State.OSC
        }
    }

    private fun completeCandidate(
        terminatorBytes: Int,
        transcript: ByteArrayOutputStream,
        items: MutableList<ProtocolStreamItem>,
    ) {
        val sequence = candidate.toByteArray()
        val payload = sequence.copyOfRange(OSC_PREFIX_BYTES, sequence.size - terminatorBytes)
        val event = parsePayload(payload)
        if (event == null) {
            transcript.write(sequence)
        } else {
            appendTranscriptItem(transcript, items)
            items += ProtocolStreamItem.Lifecycle(event)
        }
        resetCandidate()
    }

    private fun appendTranscriptItem(
        transcript: ByteArrayOutputStream,
        items: MutableList<ProtocolStreamItem>,
    ) {
        if (transcript.size() == 0) return
        items += ProtocolStreamItem.TranscriptBytes(transcript.toByteArray())
        transcript.reset()
    }

    private fun parsePayload(payload: ByteArray): ShellLifecycleEvent? {
        val text = try {
            payload.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return null
        }
        val fields = text.split(';', limit = END_FIELD_COUNT)
        if (
            fields.size < EVENT_FIELD_COUNT ||
            fields[0] != OSC_NUMBER ||
            fields[1] != PROTOCOL_NAME ||
            fields[2] != sessionNonce.value
        ) {
            return null
        }

        val commandId = runCatching { CommandId(fields[4]) }.getOrNull() ?: return null
        return when (fields[3]) {
            START_EVENT ->
                if (fields.size == EVENT_FIELD_COUNT) {
                    ShellLifecycleEvent.CommandStarted(commandId)
                } else {
                    null
                }

            OUTPUT_EVENT ->
                if (fields.size == EVENT_FIELD_COUNT) {
                    ShellLifecycleEvent.CommandOutputStarted(commandId)
                } else {
                    null
                }

            END_EVENT -> parseEndEvent(fields, commandId)
            else -> null
        }
    }

    private fun parseEndEvent(
        fields: List<String>,
        commandId: CommandId,
    ): ShellLifecycleEvent.CommandEnded? {
        if (fields.size != END_FIELD_COUNT) return null
        val exitStatus = fields[5].toIntOrNull()?.takeIf { it in 0..MAX_EXIT_STATUS }
            ?: return null
        return ShellLifecycleEvent.CommandEnded(
            commandId = commandId,
            exitStatus = exitStatus,
            currentDirectory = fields[6],
        )
    }

    private fun flushIfOversized(transcript: ByteArrayOutputStream): Boolean {
        if (candidate.size() <= maximumSequenceBytes) return false
        transcript.write(candidate.toByteArray())
        resetCandidate()
        return true
    }

    private fun resetCandidate() {
        candidate.reset()
        state = State.TEXT
    }

    private enum class State {
        TEXT,
        ESCAPE,
        OSC,
        OSC_ESCAPE,
    }

    private companion object {
        const val DEFAULT_MAXIMUM_SEQUENCE_BYTES = 8_192
        const val MINIMUM_SEQUENCE_BYTES = 32
        const val OSC_PREFIX_BYTES = 2
        const val BEL_TERMINATOR_BYTES = 1
        const val STRING_TERMINATOR_BYTES = 2
        const val EVENT_FIELD_COUNT = 5
        const val END_FIELD_COUNT = 7
        const val MAX_EXIT_STATUS = 255
        const val OSC_NUMBER = "777"
        const val PROTOCOL_NAME = "threadline"
        const val START_EVENT = "start"
        const val OUTPUT_EVENT = "output"
        const val END_EVENT = "end"
        val ESC = 0x1b.toByte()
        val OSC_INTRODUCER = ']'.code.toByte()
        val BEL = 0x07.toByte()
        val STRING_TERMINATOR = '\\'.code.toByte()
    }
}

private fun isProtocolTokenCharacter(character: Char): Boolean =
    character in 'a'..'z' ||
        character in 'A'..'Z' ||
        character in '0'..'9' ||
        character == '-' ||
        character == '_'
