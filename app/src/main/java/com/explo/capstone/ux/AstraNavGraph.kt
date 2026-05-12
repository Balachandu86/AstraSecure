package com.explo.capstone.ux

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navigation
import com.explo.capstone.BuildConfig
import com.explo.capstone.shared.AppContainer
import com.explo.capstone.shared.PackageUtils
import com.explo.capstone.shared.data.DebugResetHelper
import com.explo.capstone.transport.BurnedEvent
import com.explo.capstone.ui.AstraTheme
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

    var burnedAlert by remember { mutableStateOf<BurnedEvent?>(null) }
    LaunchedEffect(Unit) {
        container.operativeBurnedAlert.collect { event -> burnedAlert = event }
    }

    Box(Modifier.fillMaxSize()) {
    NavHost(navController, startDestination) {
        // ─── Terminated (post-wipe, no exit) ─────────────────────────────
        composable("terminated") {
            val context = LocalContext.current
            TerminatedScreen(
                onUninstall = {
                    PackageUtils.uninstallApp(context)
                },
                onDebugReset = if (BuildConfig.DEBUG) {
                    {
                        DebugResetHelper.reset(container)
                        navController.navigate("provisioning") {
                            popUpTo("terminated") { inclusive = true }
                        }
                    }
                } else null,
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
                    onProfileClick = { navController.navigate("profile") },
                    onJoinClick = { navController.navigate("redeem") },
                )
            }

            composable("redeem") {
                RedeemRoute(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }

            composable("profile") {
                ProfileRoute(
                    container = container,
                    selectedTab = selectedTab,
                    onTabSelect = onTabSelect,
                    onBack = { navController.popBackStack() },
                    onAdminClick = { navController.navigate("admin") },
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
                    onProfileClick = { navController.navigate("profile") },
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
                    onProfileClick = { navController.navigate("profile") },
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
                    onProfileClick = { navController.navigate("profile") },
                )
            }

            composable("tools") {
                ToolsRoute(
                    container = container,
                    selectedTab = selectedTab,
                    onTabSelect = onTabSelect,
                    onProfileClick = { navController.navigate("profile") },
                )
            }

            composable("panic") {
                PanicRoute(container = container, selectedTab = selectedTab, onTabSelect = onTabSelect)
            }

            composable("admin") {
                AdminRoute(
                    container = container,
                    selectedTab = selectedTab,
                    onTabSelect = onTabSelect,
                    onProfileClick = { navController.navigate("profile") },
                )
            }
        }
    } // NavHost

        burnedAlert?.let { event ->
            BurnedAlertBanner(callsign = event.callsign, onDismiss = { burnedAlert = null })
        }
    } // Box
}

@Composable
private fun BurnedAlertBanner(callsign: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .zIndex(Float.MAX_VALUE)
            .background(AstraTheme.Error)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            "[ BURNED ] OPERATIVE $callsign — IDENTITY WIPED",
            style = AstraTheme.Typography.labelSmall.copy(
                color = AstraTheme.OnPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            ),
            modifier = Modifier.padding(end = 36.dp),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd).size(28.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = AstraTheme.OnPrimary, modifier = Modifier.size(16.dp))
        }
    }
}
