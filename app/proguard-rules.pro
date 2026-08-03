# ConnectBot termlib's native library resolves every CellRun field by its Java
# name. Its consumer rule keeps the public API but CellRun's backing fields are
# private, so R8 would otherwise rename them and abort the process during
# TerminalNative initialization.
-keepclassmembers class org.connectbot.terminal.CellRun {
    <fields>;
}
