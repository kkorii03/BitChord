package com.music.bitchord.data.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The one open session to the configured server. Single-server, like the rest
 * of this app's remote libraries: [SmbAuth] publishes the parameters and
 * drops this pool on every edit, so a changed credential can never keep
 * reading on the old session.
 *
 * All share access funnels through [withShare], which reconnects once when
 * the wire died mid-flight. Only transport failures ([IOException]) retry —
 * a status answer from the server (bad password, no such share) is final and
 * fails fast instead of authenticating twice against a lockout counter.
 */
object SmbConnection {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var share: DiskShare? = null

    @Synchronized
    fun invalidate() {
        runCatching { share?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        connection = null
        client = null
    }

    fun <T> withShare(block: (DiskShare) -> T): T {
        return try {
            block(open())
        } catch (e: IOException) {
            invalidate()
            block(open())
        }
    }

    /**
     * Opens a one-off connection with the given credentials, never touching
     * [SmbAuth] or the pooled session. Used for "Test" so a wrong password
     * cannot drop the live library connection.
     */
    fun <T> withTemporaryShare(
        host: String,
        share: String,
        username: String,
        password: String,
        block: (DiskShare) -> T,
    ): T {
        val (parsedHost, parsedPort) = com.music.bitchord.data.smb.SmbConfig.splitHostPort(host)
        val shareName = share.trim().trim('/')
        require(parsedHost.isNotBlank() && shareName.isNotBlank()) { "서버와 공유 이름을 입력하십시오" }
        val newClient = SMBClient(connectionConfig())
        try {
            val newConnection = newClient.connect(parsedHost, parsedPort)
            val session: Session = newConnection.authenticate(authFor(username, password))
            val diskShare = session.connectShare(shareName) as? DiskShare
                ?: throw SmbException("\"$shareName\" is not a file share")
            return block(diskShare)
        } finally {
            runCatching { newClient.close() }
        }
    }

    @Synchronized
    private fun open(): DiskShare {
        val live = share
        if (live != null && connection?.isConnected == true) return live
        invalidate()
        val host = SmbAuth.host
        val shareName = SmbAuth.share
        require(host.isNotBlank() && shareName.isNotBlank()) { "SMB가 설정되지 않았습니다" }
        val newClient = SMBClient(connectionConfig())
        try {
            val newConnection = newClient.connect(host, SmbAuth.port)
            val session: Session = newConnection.authenticate(authFor(SmbAuth.username, SmbAuth.password))
            val diskShare = session.connectShare(shareName) as? DiskShare
                ?: throw SmbException("\"$shareName\" is not a file share")
            client = newClient
            connection = newConnection
            share = diskShare
            return diskShare
        } catch (e: Throwable) {
            runCatching { newClient.close() }
            throw e
        }
    }

    private fun connectionConfig(): SmbConfig = SmbConfig.builder()
        .withTimeout(20, TimeUnit.SECONDS)
        .withReadTimeout(30, TimeUnit.SECONDS)
        .withWriteTimeout(30, TimeUnit.SECONDS)
        .withTransactTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Never anonymous(): smbj 0.15 derives SMB3 keys from a session key an
     * anonymous login has none of, and crashes on servers that do not flag
     * the session as null (Samba) - hierynomus/smbj#872. Guest sends a real
     * NTLMv2 exchange and lands on the guest path smbj handles.
     */
    internal fun authFor(username: String, password: String): AuthenticationContext =
        if (username.isBlank() && password.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(username, password.toCharArray(), "")
        }

    class SmbException(message: String) : IOException(message)
}
