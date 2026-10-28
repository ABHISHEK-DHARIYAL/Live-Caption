package com.clearcap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private class Tab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val tabs = listOf(
        Tab("Home", Icons.Filled.Home),
        Tab("History", Icons.AutoMirrored.Filled.List),
        Tab("Dictionary", Icons.Filled.Create),
        Tab("Settings", Icons.Filled.Settings),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = LocalTokens.current.surface) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i && openId == null,
                        onClick = { tab = i; openId = null },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val id = openId
            if (id != null) DetailScreen(id, onBack = { openId = null }, onSettings = { openId = null; tab = 3 })
            else when (tab) {
                0 -> HomeScreen(onOpen = { openId = it }, onSettings = { tab = 3 })
                1 -> HistoryScreen(onOpen = { openId = it })
                2 -> DictionaryScreen()
                else -> SettingsScreen()
            }
        }
    }
}
