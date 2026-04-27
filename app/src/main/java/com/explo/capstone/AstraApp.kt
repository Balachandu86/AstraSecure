package com.explo.capstone

import android.app.Application
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.data.SeedData

/**
 * Application subclass — instantiates [AppContainer] once and reuses across all activities.
 * Registered in AndroidManifest via android:name=".AstraApp".
 */
class AstraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        val snapshot = container.persistenceManager.load()
        if (snapshot != null) {
            // Restore persisted state — skip seeding
            container.loadSnapshot(snapshot)
        } else if (BuildConfig.DEBUG) {
            // Fresh install or after clear-data: populate with seed data
            SeedData.seed(container.store)
        }
    }
}
