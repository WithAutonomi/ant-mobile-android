package com.autonomi.examples.antdemo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autonomi.examples.antdemo.SettingsScreen
import com.autonomi.examples.antdemo.WalletScreen
import com.autonomi.examples.antdemo.files.DownloadsScreen
import com.autonomi.examples.antdemo.files.UploadConfirmDialog
import com.autonomi.examples.antdemo.files.UploadsScreen
import com.google.accompanist.navigation.material.ExperimentalMaterialNavigationApi
import com.google.accompanist.navigation.material.ModalBottomSheetLayout
import com.google.accompanist.navigation.material.rememberBottomSheetNavigator
import com.reown.appkit.ui.appKitGraph

/// Bottom-nav destinations. Nodes is intentionally omitted — nodes can't run on
/// mobile — so the app is Files (home) / Wallet / Settings.
private data class Dest(val route: String, val label: String, val glyph: String)

private val DESTS = listOf(
    Dest("uploads", "Uploads", "↑"),
    Dest("downloads", "Downloads", "↓"),
    Dest("wallet", "Wallet", "◎"),
    Dest("settings", "Settings", "⚙"),
)

/// App shell: a top header + a bottom navigation bar (standard mobile pattern)
/// over the routed content. The NavHost also hosts `appKitGraph` so the
/// WalletConnect modal (a bottom sheet) can present over any screen.
@OptIn(ExperimentalMaterialNavigationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppShell() {
    val bottomSheetNavigator = rememberBottomSheetNavigator()
    val navController = rememberNavController(bottomSheetNavigator)
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val title = DESTS.firstOrNull { it.route == currentRoute }?.label ?: "Autonomi"

    // Deep-link (`autonomi://…`) navigation: jump to the requested tab.
    val deepLinkTarget by com.autonomi.examples.antdemo.deeplink.DeepLinkNav.target.collectAsState()
    LaunchedEffect(deepLinkTarget) {
        deepLinkTarget?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            com.autonomi.examples.antdemo.deeplink.DeepLinkNav.consumed()
        }
    }

    ModalBottomSheetLayout(bottomSheetNavigator = bottomSheetNavigator) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { HeaderBar(title) },
            bottomBar = { BottomNav(currentRoute, navController) },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "uploads",
                modifier = Modifier.padding(padding),
            ) {
                composable("uploads") { UploadsScreen() }
                composable("downloads") { DownloadsScreen() }
                composable("wallet") { WalletScreen(navController) }
                composable("settings") { SettingsScreen() }
                appKitGraph(navController)
            }
        }
    }

    // Quote → approve dialog; floats over any screen (desktop UploadConfirmDialog).
    UploadConfirmDialog()
}

@Composable
private fun BottomNav(currentRoute: String?, navController: NavController) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        DESTS.forEach { d ->
            NavigationBarItem(
                selected = currentRoute == d.route,
                onClick = {
                    navController.navigate(d.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Text(d.glyph, fontSize = 20.sp) },
                label = { Text(d.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/// Slim top header (desktop AppHeader: h-12, surface, bottom border, page title).
@Composable
private fun HeaderBar(title: String) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
        androidx.compose.foundation.layout.Column {
            Box(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
