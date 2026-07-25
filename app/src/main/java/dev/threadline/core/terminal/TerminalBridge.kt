package dev.threadline.core.terminal

import android.os.Looper
import dev.threadline.core.model.TerminalSize
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

interface TerminalSink {
    val size: TerminalSize

    fun clear()

    fun receive(bytes: ByteArray)
}

class TerminalBridge : TerminalSink {
    @Volatile
    private var inputHandler: (ByteArray) -> Unit = {}

    @Volatile
    private var resizeHandler: (TerminalSize) -> Unit = {}

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        looper = Looper.getMainLooper(),
        initialRows = 24,
        initialCols = 80,
        onKeyboardInput = { bytes -> inputHandler(bytes) },
        onResize = { dimensions ->
            if (dimensions.rows > 0 && dimensions.columns > 0) {
                resizeHandler(TerminalSize(dimensions.rows, dimensions.columns))
            }
        },
        autoDetectUrls = false,
    )

    override val size: TerminalSize
        get() = emulator.dimensions.let { TerminalSize(it.rows, it.columns) }

    fun bind(
        onInput: (ByteArray) -> Unit,
        onResize: (TerminalSize) -> Unit,
    ) {
        inputHandler = onInput
        resizeHandler = onResize
    }

    override fun clear() = emulator.clearScreen()

    override fun receive(bytes: ByteArray) = emulator.writeInput(bytes)
}
