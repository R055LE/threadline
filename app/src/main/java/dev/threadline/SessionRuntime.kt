package dev.threadline

import android.content.Context
import dev.threadline.core.security.SharedPreferencesKnownHostStore
import dev.threadline.core.session.SessionManager
import dev.threadline.core.ssh.AndroidSshCryptoProvider
import dev.threadline.core.ssh.ConnectBotSshClientAdapter
import dev.threadline.core.ssh.HostKeyAlgorithmPolicy
import dev.threadline.core.terminal.TerminalBridge

object SessionRuntime {
    lateinit var manager: SessionManager
        private set

    lateinit var terminal: TerminalBridge
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (::manager.isInitialized) return

        val hostKeyAlgorithms = HostKeyAlgorithmPolicy.overrideWhenEd25519Unavailable(
            AndroidSshCryptoProvider.install(),
        )
        val bridge = TerminalBridge()
        val sessionManager = SessionManager(
            adapter = ConnectBotSshClientAdapter(hostKeyAlgorithms),
            knownHostStore = SharedPreferencesKnownHostStore(
                context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE),
            ),
            terminal = bridge,
        )
        bridge.bind(
            onInput = sessionManager::send,
            onResize = sessionManager::resize,
        )

        terminal = bridge
        manager = sessionManager
    }
}
