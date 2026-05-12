package com.explo.capstone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.explo.capstone.BuildConfig
import com.explo.capstone.shared.PackageUtils
import com.explo.capstone.shared.data.DebugResetHelper
import com.explo.capstone.ui.AstraSecureTheme
import com.explo.capstone.ui.TerminatedScreen
import com.explo.capstone.ux.AstraNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-bleed dark UI — let Scaffold handle insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Tombstone lives in plain SharedPreferences so it survives wipeAll AND remains
        // readable even if AppContainer init failed (e.g. EncryptedSharedPreferences keyset
        // mismatch after a purge). Read it before touching the container.
        val tombstoned = getSharedPreferences("astra_state", Context.MODE_PRIVATE)
            .getBoolean("terminal", false)

        val container = (application as AstraApp).containerOrNull

        if (tombstoned || container == null) {
            val activity = this@MainActivity
            setContent {
                AstraSecureTheme {
                    TerminatedScreen(
                        onUninstall = {
                            PackageUtils.uninstallApp(activity)
                        },
                        onDebugReset = if (BuildConfig.DEBUG && container != null) {
                            {
                                DebugResetHelper.reset(container)
                                activity.recreate()
                            }
                        } else null,
                    )
                }
            }
            return
        }

        val start = try {
            if (container.identityManager.getUserIdentity() != null) "app" else "provisioning"
        } catch (_: Exception) {
            "provisioning"
        }

        setContent {
            AstraSecureTheme {
                val navController = rememberNavController()
                AstraNavGraph(
                    navController = navController,
                    container = container,
                    startDestination = start,
                )
            }
        }
    }
}
