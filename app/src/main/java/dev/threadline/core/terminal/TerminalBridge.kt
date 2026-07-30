package dev.threadline.core.terminal

import android.os.Looper
import dev.threadline.core.model.TerminalSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

interface TerminalSink {
    val size: TerminalSize

    fun clear()

    suspend fun receive(bytes: ByteArray)
}

class TerminalBridge : TerminalSink {
    private val modifierLock = Any()
    private val mutableModifiers = MutableStateFlow(TerminalModifiers())

    @Volatile
    private var inputHandler: (ByteArray) -> Unit = {}

    @Volatile
    private var resizeHandler: (TerminalSize) -> Unit = {}

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        // termlib uses Choreographer to bound snapshot updates to display
        // frames only when this is the main looper. A background looper makes
        // it rebuild snapshots for every PTY chunk under sustained output.
        looper = Looper.getMainLooper(),
        initialRows = 24,
        initialCols = 80,
        onKeyboardInput = { bytes ->
            inputHandler(
                TerminalKeyEncoder.keyboardInput(bytes, takeModifiers()),
            )
        },
        onResize = { dimensions ->
            if (dimensions.rows > 0 && dimensions.columns > 0) {
                resizeHandler(TerminalSize(dimensions.rows, dimensions.columns))
            }
        },
        autoDetectUrls = false,
    )

    override val size: TerminalSize
        get() = emulator.dimensions.let { TerminalSize(it.rows, it.columns) }

    internal val modifiers: StateFlow<TerminalModifiers> = mutableModifiers.asStateFlow()

    fun bind(
        onInput: (ByteArray) -> Unit,
        onResize: (TerminalSize) -> Unit,
    ) {
        inputHandler = onInput
        resizeHandler = onResize
    }

    internal fun toggleControl() {
        synchronized(modifierLock) {
            mutableModifiers.value = mutableModifiers.value.copy(
                control = !mutableModifiers.value.control,
            )
        }
    }

    internal fun toggleAlt() {
        synchronized(modifierLock) {
            mutableModifiers.value = mutableModifiers.value.copy(
                alt = !mutableModifiers.value.alt,
            )
        }
    }

    internal fun sendKey(key: TerminalKey) {
        inputHandler(TerminalKeyEncoder.extraKey(key, takeModifiers()))
    }

    internal fun clearModifiers() {
        synchronized(modifierLock) {
            mutableModifiers.value = TerminalModifiers()
        }
    }

    override fun clear() = emulator.clearScreen()

    override suspend fun receive(bytes: ByteArray) = emulator.writeInput(bytes)

    private fun takeModifiers(): TerminalModifiers = synchronized(modifierLock) {
        mutableModifiers.value.also {
            mutableModifiers.value = TerminalModifiers()
        }
    }
}
