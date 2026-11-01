package com.clearcap.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val HOME = "home"
    const val SOURCE = "source"
    const val HISTORY = "history"
    const val DETAIL = "detail/{id}"
    const val MODELS = "models"
    const val VOCAB = "vocabulary"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"
    fun detail(id: String) = "detail/$id"
}

private class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.HISTORY, "History", Icons.AutoMirrored.Filled.List),
    Tab(Routes.VOCAB, "Vocabulary", Icons.Filled.Create),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

fun NavController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (route == null || tabs.any { it.route == route }) {
                NavigationBar(containerColor = LocalTokens.current.surface) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            NavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(nav) }
                composable(Routes.SOURCE) { SourceScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.HISTORY) { HistoryScreen(onOpen = { nav.navigate(Routes.detail(it)) }) }
                composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                    DetailScreen(id = e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onModels = { nav.navigate(Routes.MODELS) })
                }
                composable(Routes.MODELS) { ModelsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.VOCAB) { VocabularyScreen() }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.PRIVACY) { InfoScreen("Privacy", PRIVACY_TEXT, onBack = { nav.popBackStack() }) }
                composable(Routes.ABOUT) { InfoScreen("About and licenses", ABOUT_TEXT, onBack = { nav.popBackStack() }) }
            }
        }
    }
}
