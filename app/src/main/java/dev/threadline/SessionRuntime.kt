package dev.threadline

import android.content.Context
import dev.threadline.core.session.SessionManager
import dev.threadline.core.ssh.AndroidSshCryptoProvider
import dev.threadline.core.ssh.ConnectBotSshClientAdapter
import dev.threadline.core.ssh.HostKeyAlgorithmPolicy
import dev.threadline.core.terminal.TerminalBridge
import dev.threadline.data.db.ThreadlineDatabase
import dev.threadline.data.host.RoomKnownHostStore
import dev.threadline.data.key.AndroidKeystorePrivateKeyCipher
import dev.threadline.data.key.EncryptedImportedPrivateKeyStore
import dev.threadline.data.profile.RoomHostProfileStore
import dev.threadline.data.transcript.RoomTranscriptHistoryStore

object SessionRuntime {
    lateinit var manager: SessionManager
        private set

    lateinit var terminal: TerminalBridge
        private set

    internal lateinit var database: ThreadlineDatabase
        private set

    internal lateinit var importedPrivateKeys: EncryptedImportedPrivateKeyStore
        private set

    internal lateinit var knownHosts: RoomKnownHostStore
        private set

    internal lateinit var hostProfiles: RoomHostProfileStore
        private set

    internal lateinit var transcriptHistory: RoomTranscriptHistoryStore
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (::manager.isInitialized) return

        val hostKeyAlgorithms = HostKeyAlgorithmPolicy.overrideWhenEd25519Unavailable(
            AndroidSshCryptoProvider.install(),
        )
        val bridge = TerminalBridge()
        val threadlineDatabase = ThreadlineDatabase.create(context)
        val importedKeyStore = EncryptedImportedPrivateKeyStore(
            dao = threadlineDatabase.importedPrivateKeys(),
            cipher = AndroidKeystorePrivateKeyCipher(),
        )
        val hostProfileStore = RoomHostProfileStore(threadlineDatabase.hostProfiles())
        val transcriptHistoryStore = RoomTranscriptHistoryStore(
            threadlineDatabase.transcriptArchives(),
        )
        val knownHostStore = RoomKnownHostStore(
            dao = threadlineDatabase.knownHosts(),
            legacyPreferences = context.getSharedPreferences(
                "known_hosts",
                Context.MODE_PRIVATE,
            ),
        )
        val sessionManager = SessionManager(
            adapter = ConnectBotSshClientAdapter(hostKeyAlgorithms),
            knownHostStore = knownHostStore,
            terminal = bridge,
            transcriptArchiveSink = transcriptHistoryStore,
        )
        bridge.bind(
            onInput = sessionManager::send,
            onResize = sessionManager::resize,
        )

        database = threadlineDatabase
        importedPrivateKeys = importedKeyStore
        knownHosts = knownHostStore
        hostProfiles = hostProfileStore
        transcriptHistory = transcriptHistoryStore
        terminal = bridge
        manager = sessionManager
    }
}
