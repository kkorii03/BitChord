package com.music.bitchord.auth

import android.content.Context
import com.music.bitchord.BitChordApplication
import com.music.bitchord.data.net.NetworkReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Whether a YouTube account is signed in, as one flow for the whole process.
 *
 * [MainViewModel][com.music.bitchord.ui.MainViewModel] owns sign-in and sign-out
 * and writes it; the playback service reads it, so the surfaces it draws — the
 * notification, the home-screen widgets, the watch — offer Like on exactly the
 * tracks the player does. A service started without the app (a widget tap, the
 * watch) seeds it from [AuthStore] the same way the view model does.
 */
object SignedIn {

    @Volatile private var state: MutableStateFlow<Boolean>? = null

    fun state(context: Context): MutableStateFlow<Boolean> =
        state ?: synchronized(this) {
            state ?: newState(context).also { state = it }
        }

    private fun newState(context: Context): MutableStateFlow<Boolean> {
        val flow = MutableStateFlow(false)
        val application = context.applicationContext
        val store = if (application is BitChordApplication) {
            runCatching { BitChordApplication.authStore }.getOrNull()
        } else {
            null
        } ?: AuthStore(application)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            NetworkReadiness.await()
            flow.value = runCatching { store.isSignedIn }.getOrDefault(false)
        }
        return flow
    }
}
