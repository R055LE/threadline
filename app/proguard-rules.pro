# ConnectBot termlib's native library resolves every CellRun field by its Java
# name. Its consumer rule keeps the public API but CellRun's backing fields are
# private, so R8 would otherwise rename them and abort the process during
# TerminalNative initialization.
-keepclassmembers class org.connectbot.terminal.CellRun {
    <fields>;
}

# The same native library constructs scrollback ScreenCell objects by assigning
# their private fields directly. Resizing the raw terminal can pop scrollback,
# so this contract is reached only when the terminal viewport opens or changes.
-keepclassmembers class org.connectbot.terminal.ScreenCell {
    <fields>;
}

# cbssh 0.4.2 registers these bundled JCA implementations by a class name
# derived from Ed25519Provider's runtime package. R8 must preserve the provider
# package and both name-loaded implementations or host-key verification fails
# before authentication.
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519Provider { *; }
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519KeyFactory { *; }
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519KeyPairGenerator { *; }
