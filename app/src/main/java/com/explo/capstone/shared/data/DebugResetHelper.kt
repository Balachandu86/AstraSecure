package com.explo.capstone.shared.data

import com.explo.capstone.BuildConfig
import com.explo.capstone.shared.AppContainer

/**
 * Debug-only utility that resets the device to a clean pre-provisioning state.
 *
 * Only callable when [BuildConfig.DEBUG] is true — call sites should guard with
 * the same check so the dead-code eliminator strips this entirely from release APKs.
 *
 * Reset sequence (order matters):
 *   1. Clear tombstone   — allows MainActivity to route away from TerminatedScreen
 *   2. Wipe identity     — removes EncryptedSharedPreferences + identity Keystore key
 *   3. Invalidate keys   — removes all remaining "astra_*" Keystore aliases
 *   4. Clear store       — empties in-memory StateFlows
 *   5. Clear persistence — deletes astra_store.json so seed data reloads on next launch
 *   6. Clear event log   — fresh slate for the security dashboard
 */
object DebugResetHelper {

    fun reset(container: AppContainer) {
        check(BuildConfig.DEBUG) { "DebugResetHelper must never be called in release builds" }

        runCatching { container.identityManager.clearTombstone() }
        runCatching { container.signalStore.wipeAll() }
        runCatching { container.identityManager.wipeAll() }
        runCatching { container.cryptoEngine.invalidateAllKeys() }
        container.store.clear()
        container.persistenceManager.clear()
        container.securityEventLog.clear()
    }
}
