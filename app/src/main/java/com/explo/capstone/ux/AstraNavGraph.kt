package com.explo.capstone.ux

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navigation
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.ui.NavTab
import com.explo.capstone.ui.TerminatedScreen

/**
 * Central navigation graph — all routes now wire to functional screens.
 */
@Composable
fun AstraNavGraph(
    navController: NavHostController,
    container: AppContainer,
    startDestination: String,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedTab = NavTab.fromRoute(backStackEntry?.destination?.route)

    val onTabSelect: (NavTab) -> Unit = { tab ->
        val route = when (tab) {
            NavTab.MISSIONS -> "missions"
            NavTab.SECURITY -> "security"
            NavTab.TOOLS    -> "tools"
            NavTab.PANIC    -> "panic"
        }
        navController.navigate(route) {
            popUpTo("app") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(navController, startDestination) {
        // ─── Terminated (post-wipe, no exit) ─────────────────────────────
        composable("terminated") {
            val context = LocalContext.current
            TerminatedScreen(
                onUninstall = {
                    // ACTION_DELETE is unreliable on emulators; fall back to App Info which
                    // always surfaces the Uninstall button regardless of device/API level.
                    val deleteIntent = Intent(Intent.ACTION_DELETE,
                        Uri.fromParts("package", context.packageName, null))
                    try {
                        context.startActivity(deleteIntent)
                    } catch (_: ActivityNotFoundException) {
                        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null))
                        context.startActivity(settingsIntent)
                    }
                }
            )
        }

        // ─── Provisioning (first-launch only, no app shell) ──────────────
        composable("provisioning") {
            ProvisioningRoute(
                container = container,
                onProvisioned = {
                    navController.navigate("app") {
                        popUpTo("provisioning") { inclusive = true }
                    }
                },
            )
        }

        // ─── App nested graph ────────────────────────────────────────────
        navigation(startDestination = "missions", route = "app") {

            composable("missions") {
                MissionsRoute(
                    container = container,
                    selectedTab = selectedTab,
                    onTabSelect = onTabSelect,
                    onMissionClick = { missionId -> navController.navigate("missions/$missionId") },
                )
            }

            composable("missions/{id}") { backStack ->
                val missionId = backStack.arguments?.getString("id") ?: return@composable
                ChannelListRoute(
                    missionId = missionId,
                    container = container,
                    selectedTab = selectedTab,
                    onTabSelect = onTabSelect,
                    onChannelClick = { channelId -> navController.navigate("missions/$missionId/channels/$channelId") },
                    onBack = { navController.popBackStack() },
                )
            }

            composable("missions/{id}/channels/{channelId}") { backStack ->
                val missionId = backStack.arguments?.getString("id") ?: return@composable
                val channelId = backStack.arguments?.getString("channelId") ?: return@composable
                ChatRoute(
                    channelId = channelId,
                    missionId = missionId,
                    container = container,
                    selectedTab = selectedTab,
                    onTabSelect = onTabSelect,
                    onBack = { navController.popBackStack() },
                )
            }

            composable("security") {
                SecurityRoute(
                    container = container,
                    selectedTab = selectedTab,
                    onTabSelect = onTabSelect,
                    onNavigateToPanic = {
                        navController.navigate("panic") {
                            popUpTo("app") { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToAdmin = { navController.navigate("admin") },
                )
            }

            composable("tools") {
                ToolsRoute(container = container, selectedTab = selectedTab, onTabSelect = onTabSelect)
            }

            composable("panic") {
                PanicRoute(container = container, selectedTab = selectedTab, onTabSelect = onTabSelect)
            }

            composable("admin") {
                AdminRoute(container = container, selectedTab = selectedTab, onTabSelect = onTabSelect)
            }
        }
    }
}
