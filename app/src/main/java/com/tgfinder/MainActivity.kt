package com.tgfinder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tgfinder.telegram.AuthState
import com.tgfinder.ui.common.LocalContainer
import com.tgfinder.ui.detail.DetailScreen
import com.tgfinder.ui.downloads.DownloadsScreen
import com.tgfinder.ui.login.LoginScreen
import com.tgfinder.ui.search.SearchScreen
import com.tgfinder.ui.settings.SettingsScreen
import com.tgfinder.ui.theme.ImdbYellow
import com.tgfinder.ui.theme.ThemeMode
import com.tgfinder.ui.theme.TgFinderTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Set when opened from the download notification. */
    private val openRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        val container = (application as TgFinderApp).container
        setContent {
            val theme by container.prefs.theme.collectAsState()
            val dark = theme == ThemeMode.DARK || (theme == ThemeMode.SYSTEM && androidx.compose.foundation.isSystemInDarkTheme())
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.Black.toArgb())
                else SystemBarStyle.light(Color.White.toArgb(), Color.Black.toArgb())
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            CompositionLocalProvider(LocalContainer provides container) {
                TgFinderTheme(theme) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppRoot(openRequest)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(EXTRA_OPEN)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_DOWNLOADS = "downloads"
    }
}

@Composable
private fun AppRoot(openRequest: MutableStateFlow<String?>) {
    val container = LocalContainer.current
    val config by container.prefs.config.collectAsState()
    val auth by container.td.auth.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    when {
        !config.hasTelegram -> Box(Modifier.safeDrawingPadding()) { SettingsScreen(setupMode = true) }
        auth == AuthState.Ready -> MainScaffold(openRequest)
        showSettings -> {
            BackHandler { showSettings = false }
            Box(Modifier.safeDrawingPadding()) { SettingsScreen(setupMode = false, onBack = { showSettings = false }) }
        }
        else -> Box(Modifier.safeDrawingPadding()) { LoginScreen(onOpenSettings = { showSettings = true }) }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("search", "Search", Icons.Filled.Search),
    Tab("downloads", "Downloads", Icons.Filled.Download),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun MainScaffold(openRequest: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val request by openRequest.collectAsState()

    fun goTab(tab: String) = nav.navigate(tab) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    LaunchedEffect(request) {
        if (request == MainActivity.OPEN_DOWNLOADS) {
            goTab("downloads")
            openRequest.value = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (route in TABS.map { it.route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { goTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                indicatorColor = ImdbYellow,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "search", modifier = Modifier.padding(padding)) {
            composable("search") {
                SearchScreen(
                    onOpenDetail = { key -> nav.navigate("detail/${android.net.Uri.encode(key)}") },
                    onOpenSettings = { goTab("settings") },
                )
            }
            composable("downloads") { DownloadsScreen() }
            composable("settings") { SettingsScreen(setupMode = false) }
            composable("detail/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                val key = android.net.Uri.decode(entry.arguments?.getString("key").orEmpty())
                DetailScreen(groupKey = key, onBack = { nav.popBackStack() })
            }
        }
    }
}
