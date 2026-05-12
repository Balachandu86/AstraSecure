package com.explo.capstone

import android.app.Application
import com.explo.capstone.shared.AppContainer

/**
 * Application subclass — instantiates [AppContainer] once and reuses across all activities.
 * Registered in AndroidManifest via android:name=".AstraApp".
 */
class AstraApp : Application() {

    // Nullable so callers can detect init failure (e.g. EncryptedSharedPreferences keyset
    // mismatch after a purge) and route to TerminatedScreen instead of crashing.
    var containerOrNull: AppContainer? = null
        private set

    val container: AppContainer
        get() = containerOrNull ?: error("AppContainer not initialized")

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val c = AppContainer(this)
            containerOrNull = c

            // Restore cached state (missions, channels, schema from last sync) for offline resilience.
            // The AppContainer startup coroutine calls syncFromServer() immediately after, which
            // overwrites this with fresh server data if a JWT is present.
            val snapshot = c.persistenceManager.load()
            if (snapshot != null) c.loadSnapshot(snapshot)
        }.onFailure { e ->
            android.util.Log.e("AstraApp", "Failed to initialize AppContainer", e)
        }
    }
}
