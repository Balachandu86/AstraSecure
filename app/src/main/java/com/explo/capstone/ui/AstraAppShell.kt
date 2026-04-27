package com.explo.capstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Tab enum ────────────────────────────────────────────────────────────────

enum class NavTab(val label: String, val icon: ImageVector, val activeColor: Color) {
    MISSIONS("MISSIONS", Icons.Filled.Assignment,     AstraTheme.Primary),
    SECURITY("SECURITY", Icons.Outlined.VerifiedUser,  AstraTheme.Primary),
    TOOLS("TOOLS",       Icons.Outlined.Construction,  AstraTheme.Primary),
    PANIC("PANIC",       Icons.Outlined.Warning,       AstraTheme.Error);

    companion object {
        fun fromRoute(route: String?): NavTab = when {
            route == null              -> MISSIONS
            route.startsWith("missions") -> MISSIONS
            route == "security"        -> SECURITY
            route == "tools"           -> TOOLS
            route == "panic"           -> PANIC
            else                       -> MISSIONS
        }
    }
}

// ─── App shell ───────────────────────────────────────────────────────────────

@Composable
fun AstraAppShell(
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    topBarContent: @Composable () -> Unit = { DefaultAstraTopBar() },
    body: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { topBarContent() },
        bottomBar = { AstraBottomBar(selectedTab, onTabSelect) },
        containerColor = AstraTheme.SurfaceDim,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        body(padding)
    }
}

// ─── Default top bar ─────────────────────────────────────────────────────────

@Composable
fun DefaultAstraTopBar(
    callsign: String = "OPERATOR",
    clearanceLabel: String = "LEVEL_??_ENCRYPTED",
) {
    AstraTopBar(callsign = callsign, clearanceLabel = clearanceLabel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstraTopBar(
    callsign: String = "GHOST_OPS_09",
    clearanceLabel: String = "LEVEL_09_ENCRYPTED",
    navigationIcon: @Composable (() -> Unit)? = null,
    titleOverride: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    TopAppBar(
        navigationIcon = { navigationIcon?.invoke() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = AstraTheme.Primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    titleOverride ?: "ASTRA // SECURE",
                    style = AstraTheme.Typography.headlineSmall.copy(
                        color = AstraTheme.Primary,
                        letterSpacing = 4.sp,
                        fontSize = 17.sp
                    )
                )
            }
        },
        actions = {
            actions?.invoke(this)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    callsign.uppercase(),
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
                Text(
                    clearanceLabel,
                    style = AstraTheme.Typography.labelSmall.copy(
                        color = AstraTheme.Tertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AstraTheme.SurfaceContainerHigh)
                    .border(1.dp, AstraTheme.OutlineVariant.copy(alpha = 0.2f))
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile",
                    tint = AstraTheme.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AstraTheme.SurfaceDim)
    )
}

// ─── Bottom bar ──────────────────────────────────────────────────────────────

@Composable
fun AstraBottomBar(selectedTab: NavTab, onTabSelect: (NavTab) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Top border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AstraTheme.OutlineVariant.copy(alpha = 0.15f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AstraTheme.SurfaceDim)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            NavTab.entries.forEach { tab ->
                NavItem(tab, selectedTab, onTabSelect)
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: NavTab,
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
) {
    val selected = tab == selectedTab
    val itemColor = if (selected) tab.activeColor else Color(0xFF6A7B92)

    Column(
        modifier = Modifier
            .background(if (selected) AstraTheme.SurfaceContainerHighest else Color.Transparent)
            .clickable { onTabSelect(tab) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = itemColor,
            modifier = Modifier.size(24.dp)
        )
        Text(
            tab.label,
            style = AstraTheme.Typography.labelSmall.copy(
                color = itemColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontSize = 9.sp
            )
        )
    }
}
