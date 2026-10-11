package app.scanforge.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.scanforge.android.ui.home.HomeScreen
import app.scanforge.android.ui.measurements.MeasurementsScreen
import app.scanforge.android.ui.projects.ProjectDetailScreen
import app.scanforge.android.ui.projects.ProjectsScreen
import app.scanforge.android.ui.scan.NewScanScreen
import app.scanforge.android.ui.scan.ReconstructionScreen
import app.scanforge.android.ui.scan.ScanScreen
import app.scanforge.android.ui.settings.SettingsScreen
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.android.ui.transfer.TransferScreen

private data class Section(val route: String, val label: String, val icon: ImageVector)

private val sections = listOf(
    Section("home", "Scan", Icons.Filled.ViewInAr),
    Section("projects", "Projects", Icons.Filled.FolderOpen),
    Section("measurements", "Measurements", Icons.Filled.Straighten),
    Section("transfer", "Transfer", Icons.Filled.SyncAlt),
    Section("settings", "Settings", Icons.Filled.Settings),
)

object Routes {
    fun scan(projectId: String, mode: String, preset: String, category: String) = "scan/$projectId?mode=$mode&preset=$preset&category=$category"
    fun reconstruct(projectId: String) = "reconstruct/$projectId"
    fun project(projectId: String) = "project/$projectId"
}

/** Bottom bar on phones, navigation rail on tablets / landscape (width ≥ 600 dp). */
@Composable
fun ScanForgeNavHost() {
    val nav = rememberNavController()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showNav = route in sections.map { it.route }
    Scaffold(
        containerColor = SfColors.Charcoal,
        bottomBar = {
            if (showNav && !wide) NavigationBar(containerColor = SfColors.Panel) {
                sections.forEach { s ->
                    NavigationBarItem(selected = route == s.route, onClick = { navigateTop(nav, s.route) },
                        icon = { Icon(s.icon, contentDescription = null) }, label = { Text(s.label) })
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (showNav && wide) NavigationRail(containerColor = SfColors.Panel) {
                sections.forEach { s ->
                    NavigationRailItem(selected = route == s.route, onClick = { navigateTop(nav, s.route) },
                        icon = { Icon(s.icon, contentDescription = null) }, label = { Text(s.label) })
                }
            }
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                composable("home") { HomeScreen(nav) }
                composable("projects") { ProjectsScreen(nav) }
                composable("measurements") { MeasurementsScreen() }
                composable("transfer") { TransferScreen() }
                composable("settings") { SettingsScreen() }
                composable("newScan") { NewScanScreen(nav) }
                composable(
                    "scan/{projectId}?mode={mode}&preset={preset}&category={category}",
                    arguments = listOf(
                        navArgument("projectId") { type = NavType.StringType },
                        navArgument("mode") { type = NavType.StringType; defaultValue = "DEPTH" },
                        navArgument("preset") { type = NavType.StringType; defaultValue = "STANDARD" },
                        navArgument("category") { type = NavType.StringType; defaultValue = "OTHER" },
                    ),
                ) { ScanScreen(nav) }
                composable("reconstruct/{projectId}") { ReconstructionScreen(nav) }
                composable("project/{projectId}") { ProjectDetailScreen(nav) }
            }
        }
    }
}

private fun navigateTop(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
