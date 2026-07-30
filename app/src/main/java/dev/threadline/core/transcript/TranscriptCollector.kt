package dev.threadline.core.transcript

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class TranscriptCollector(
    private val maximumRenderedCharacters: Int = DEFAULT_MAXIMUM_RENDERED_CHARACTERS,
    private val tabWidth: Int = DEFAULT_TAB_WIDTH,
    private val maximumEscapeSequenceBytes: Int = DEFAULT_MAXIMUM_ESCAPE_SEQUENCE_BYTES,
) {
    private val lines = ArrayDeque<MutableList<StyledCharacter>>().apply {
        addLast(mutableListOf())
    }
    private val textBytes = ByteArrayOutputStream()
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var undecodedBytes = ByteArray(0)
    private var parserState = ParserState.TEXT
    private val escapeSequence = ByteArrayOutputStream()
    private var style = TranscriptStyle()
    private var cursor = 0
    private var replaceLineOnWrite = false
    private var truncated = false
    private var approximate = false
    private var interactiveHint: InteractiveTerminalHint? = null
    private var byteCount = 0L
    private var renderedCharacters = 0

    init {
        require(maximumRenderedCharacters > 0)
        require(tabWidth > 0)
        require(maximumEscapeSequenceBytes >= MINIMUM_ESCAPE_SEQUENCE_BYTES)
    }

    fun consume(bytes: ByteArray) {
        byteCount += bytes.size
        bytes.forEach(::consumeByte)
        flushText(endOfInput = false)
        trimToLimit()
    }

    fun finish() {
        flushText(endOfInput = true)
        if (parserState != ParserState.TEXT) {
            approximate = true
            escapeSequence.reset()
            parserState = ParserState.TEXT
        }
        trimToLimit()
    }

    fun snapshot(): CommandOutput {
        val text = buildString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                line.forEach { append(it.character) }
            }
        }
        val runs = mutableListOf<StyledRun>()
        var offset = 0
        var runStart = -1
        var runStyle: TranscriptStyle? = null

        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) {
                if (runStart >= 0) {
                    runs += StyledRun(runStart, offset, requireNotNull(runStyle))
                    runStart = -1
                    runStyle = null
                }
                offset += 1
            }
            line.forEach { cell ->
                if (cell.style == TranscriptStyle()) {
                    if (runStart >= 0) {
                        runs += StyledRun(runStart, offset, requireNotNull(runStyle))
                        runStart = -1
                        runStyle = null
                    }
                } else if (cell.style != runStyle) {
                    if (runStart >= 0) {
                        runs += StyledRun(runStart, offset, requireNotNull(runStyle))
                    }
                    runStart = offset
                    runStyle = cell.style
                }
                offset += 1
            }
        }
        if (runStart >= 0) {
            runs += StyledRun(runStart, offset, requireNotNull(runStyle))
        }

        return CommandOutput(
            plainText = text,
            styledRuns = runs,
            truncated = truncated,
            approximate = approximate,
            byteCount = byteCount,
            interactiveHint = interactiveHint,
        )
    }

    private fun consumeByte(byte: Byte) {
        when (parserState) {
            ParserState.TEXT -> consumeTextByte(byte)
            ParserState.ESCAPE -> consumeEscapeByte(byte)
            ParserState.CSI -> consumeCsiByte(byte)
            ParserState.OSC -> consumeOscByte(byte)
            ParserState.OSC_ESCAPE -> consumeOscEscapeByte(byte)
        }
    }

    private fun consumeTextByte(byte: Byte) {
        when (byte) {
            ESC -> {
                flushText(endOfInput = true)
                escapeSequence.write(byte.toInt())
                parserState = ParserState.ESCAPE
            }

            LF -> {
                flushText(endOfInput = true)
                lineFeed()
            }

            CR -> {
                flushText(endOfInput = true)
                carriageReturn()
            }

            BACKSPACE -> {
                flushText(endOfInput = true)
                if (cursor > 0) cursor -= 1
            }

            TAB -> {
                flushText(endOfInput = true)
                val spaces = tabWidth - (cursor % tabWidth)
                repeat(spaces) { writeCharacter(' ') }
            }

            BEL -> Unit

            else -> {
                val unsigned = byte.toInt() and 0xff
                if (unsigned < SPACE || unsigned == DELETE) {
                    flushText(endOfInput = true)
                    approximate = true
                } else {
                    textBytes.write(unsigned)
                }
            }
        }
    }

    private fun consumeEscapeByte(byte: Byte) {
        escapeSequence.write(byte.toInt())
        when (byte) {
            CSI_INTRODUCER -> parserState = ParserState.CSI
            OSC_INTRODUCER -> {
                approximate = true
                parserState = ParserState.OSC
            }

            ESC -> {
                approximate = true
                escapeSequence.reset()
                escapeSequence.write(byte.toInt())
            }

            else -> finishUnsupportedEscape()
        }
    }

    private fun consumeCsiByte(byte: Byte) {
        escapeSequence.write(byte.toInt())
        if (escapeSequence.size() > maximumEscapeSequenceBytes) {
            finishUnsupportedEscape()
            return
        }
        val unsigned = byte.toInt() and 0xff
        if (unsigned !in CSI_FINAL_MIN..CSI_FINAL_MAX) return

        interactiveHint = interactiveHint ?: InteractiveTerminalHintDetector.detectCsi(
            escapeSequence.toByteArray(),
        )
        if (byte == SGR_FINAL) {
            applySgr(escapeSequence.toByteArray())
            escapeSequence.reset()
            parserState = ParserState.TEXT
        } else {
            finishUnsupportedEscape()
        }
    }

    private fun consumeOscByte(byte: Byte) {
        escapeSequence.write(byte.toInt())
        if (escapeSequence.size() > maximumEscapeSequenceBytes) {
            finishUnsupportedEscape()
            return
        }
        when (byte) {
            BEL -> {
                escapeSequence.reset()
                parserState = ParserState.TEXT
            }

            ESC -> parserState = ParserState.OSC_ESCAPE
        }
    }

    private fun consumeOscEscapeByte(byte: Byte) {
        escapeSequence.write(byte.toInt())
        if (escapeSequence.size() > maximumEscapeSequenceBytes) {
            finishUnsupportedEscape()
            return
        }
        parserState = when (byte) {
            STRING_TERMINATOR -> {
                escapeSequence.reset()
                ParserState.TEXT
            }

            ESC -> ParserState.OSC_ESCAPE
            else -> ParserState.OSC
        }
    }

    private fun finishUnsupportedEscape() {
        approximate = true
        escapeSequence.reset()
        parserState = ParserState.TEXT
    }

    private fun flushText(endOfInput: Boolean) {
        if (textBytes.size() == 0 && undecodedBytes.isEmpty()) {
            if (endOfInput) decoder.reset()
            return
        }
        val inputBytes = undecodedBytes + textBytes.toByteArray()
        textBytes.reset()
        val input = ByteBuffer.wrap(inputBytes)
        val output = CharBuffer.allocate(maxOf(MINIMUM_CHARACTER_BUFFER, inputBytes.size * 2))
        val result = decoder.decode(input, output, endOfInput)
        check(!result.isOverflow)
        output.flip()
        while (output.hasRemaining()) {
            writeCharacter(output.get())
        }
        undecodedBytes = if (endOfInput) {
            decoder.flush(CharBuffer.allocate(MINIMUM_CHARACTER_BUFFER))
            decoder.reset()
            ByteArray(0)
        } else {
            ByteArray(input.remaining()).also(input::get)
        }
    }

    private fun writeCharacter(character: Char) {
        val line = lines.last()
        if (replaceLineOnWrite) {
            renderedCharacters -= line.size
            line.clear()
            cursor = 0
            replaceLineOnWrite = false
        }
        val styled = StyledCharacter(character, style)
        if (cursor < line.size) {
            line[cursor] = styled
        } else {
            line += styled
            renderedCharacters += 1
        }
        cursor += 1
    }

    private fun lineFeed() {
        lines.addLast(mutableListOf())
        renderedCharacters += 1
        cursor = 0
        replaceLineOnWrite = false
    }

    private fun carriageReturn() {
        cursor = 0
        replaceLineOnWrite = true
    }

    private fun applySgr(sequence: ByteArray) {
        val parameters = sequence
            .copyOfRange(CSI_PREFIX_BYTES, sequence.size - 1)
            .decodeToString()
        val codes = if (parameters.isEmpty()) {
            listOf(0)
        } else {
            parameters.split(';').map { field ->
                if (field.isEmpty()) 0 else field.toIntOrNull()
            }
        }
        if (codes.any { it == null }) {
            approximate = true
            return
        }

        var index = 0
        while (index < codes.size) {
            val code = requireNotNull(codes[index])
            when (code) {
                0 -> style = TranscriptStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                9 -> style = style.copy(strikethrough = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                29 -> style = style.copy(strikethrough = false)
                in 30..37 -> style = style.copy(
                    foreground = AnsiColor.Indexed(code - 30),
                )

                39 -> style = style.copy(foreground = null)
                in 40..47 -> style = style.copy(
                    background = AnsiColor.Indexed(code - 40),
                )

                49 -> style = style.copy(background = null)
                in 90..97 -> style = style.copy(
                    foreground = AnsiColor.Indexed(code - 90 + BRIGHT_COLOR_OFFSET),
                )

                in 100..107 -> style = style.copy(
                    background = AnsiColor.Indexed(code - 100 + BRIGHT_COLOR_OFFSET),
                )

                38, 48 -> {
                    val parsed = parseExtendedColor(codes, index + 1)
                    if (parsed == null) {
                        approximate = true
                    } else {
                        style = if (code == 38) {
                            style.copy(foreground = parsed.color)
                        } else {
                            style.copy(background = parsed.color)
                        }
                        index = parsed.lastIndex
                    }
                }

                else -> approximate = true
            }
            index += 1
        }
    }

    private fun parseExtendedColor(
        codes: List<Int?>,
        modeIndex: Int,
    ): ParsedColor? {
        return when (codes.getOrNull(modeIndex)) {
            5 -> codes.getOrNull(modeIndex + 1)
                ?.takeIf { it in 0..255 }
                ?.let { ParsedColor(AnsiColor.Indexed(it), modeIndex + 1) }

            2 -> {
                val red = codes.getOrNull(modeIndex + 1)
                val green = codes.getOrNull(modeIndex + 2)
                val blue = codes.getOrNull(modeIndex + 3)
                if (
                    red != null && red in 0..255 &&
                    green != null && green in 0..255 &&
                    blue != null && blue in 0..255
                ) {
                    ParsedColor(
                        color = AnsiColor.Rgb(red, green, blue),
                        lastIndex = modeIndex + 3,
                    )
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private fun trimToLimit() {
        if (renderedCharacters <= maximumRenderedCharacters) return
        truncated = true

        while (
            lines.size > 1 &&
            renderedCharacters - lines.first().size - 1 >= maximumRenderedCharacters
        ) {
            renderedCharacters -= lines.removeFirst().size + 1
        }
        val excess = renderedCharacters - maximumRenderedCharacters
        if (excess > 0) {
            val first = lines.first()
            val removed = minOf(excess, first.size)
            first.subList(0, removed).clear()
            renderedCharacters -= removed
            if (lines.size == 1) {
                cursor = (cursor - removed).coerceAtLeast(0)
            }
        }
    }

    private data class StyledCharacter(
        val character: Char,
        val style: TranscriptStyle,
    )

    private data class ParsedColor(
        val color: AnsiColor,
        val lastIndex: Int,
    )

    private enum class ParserState {
        TEXT,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESCAPE,
    }

    companion object {
        const val DEFAULT_MAXIMUM_RENDERED_CHARACTERS = 128 * 1024
        const val DEFAULT_TAB_WIDTH = 8
        const val DEFAULT_MAXIMUM_ESCAPE_SEQUENCE_BYTES = 4 * 1024
        private const val MINIMUM_ESCAPE_SEQUENCE_BYTES = 16
        private const val MINIMUM_CHARACTER_BUFFER = 16
        private const val CSI_PREFIX_BYTES = 2
        private const val CSI_FINAL_MIN = 0x40
        private const val CSI_FINAL_MAX = 0x7e
        private const val SPACE = 0x20
        private const val DELETE = 0x7f
        private const val BRIGHT_COLOR_OFFSET = 8
        private val ESC = 0x1b.toByte()
        private val LF = 0x0a.toByte()
        private val CR = 0x0d.toByte()
        private val BACKSPACE = 0x08.toByte()
        private val TAB = 0x09.toByte()
        private val BEL = 0x07.toByte()
        private val CSI_INTRODUCER = '['.code.toByte()
        private val OSC_INTRODUCER = ']'.code.toByte()
        private val STRING_TERMINATOR = '\\'.code.toByte()
        private val SGR_FINAL = 'm'.code.toByte()
    }
}
