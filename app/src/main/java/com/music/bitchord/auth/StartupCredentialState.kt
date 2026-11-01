package com.music.bitchord.auth

import com.music.bitchord.data.net.NetworkReadiness
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class StartupCredentials(
    val sessions: List<GoogleAccountSession>,
    val activeAccountId: String?,
    val activeProfileId: String?,
    val selectedChannelKey: String?,
    val selectedChannelName: String?,
    val signedIn: Boolean,
)

internal class StartupCredentialState(
    private val store: AuthStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(): StartupCredentials = withContext(dispatcher) {
        NetworkReadiness.await()
        store.ensureReady()
        val session = store.activeSession
        StartupCredentials(
            sessions = store.sessions,
            activeAccountId = session?.accountId,
            activeProfileId = store.activeProfileId,
            selectedChannelKey = session?.activeProfileId
                ?: store.channelPageId
                ?: store.channelDataSyncId,
            selectedChannelName = session?.profiles
                ?.firstOrNull { it.profileId == store.activeProfileId }
                ?.name
                ?: store.channelName,
            signedIn = store.isSignedIn,
        )
    }
}
