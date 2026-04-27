package com.explo.capstone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.explo.capstone.ui.AstraSecureTheme
import com.explo.capstone.ux.AstraNavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-bleed dark UI — let Scaffold handle insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val container = (application as AstraApp).container

        // Tombstone check first (survives wipeAll), then identity check
        val start = try {
            when {
                container.identityManager.readTombstone() -> "terminated"
                container.identityManager.getUserIdentity() != null -> "app"
                else -> "provisioning"
            }
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
