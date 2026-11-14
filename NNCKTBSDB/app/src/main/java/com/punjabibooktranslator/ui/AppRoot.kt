package com.punjabibooktranslator.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.punjabibooktranslator.data.ReaderPrefs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.punjabibooktranslator.AppContainer

private const val LIBRARY = "library"
private const val QUEUES = "queues"
private const val SETTINGS = "settings"
private const val READER = "reader/{bookId}"
private const val QUEUE = "queue/{bookId}"
private const val GEMINI = "gemini"
private const val TOOL = "tool/{kind}/{bookId}"
private const val TRANSLATE = "translate/{bookId}/{block}/{page}?jobId={jobId}"

@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route == LIBRARY || route == QUEUES || route == SETTINGS

    val ai by container.prefs.prefs.collectAsStateWithLifecycle(initialValue = ReaderPrefs())
    CompositionLocalProvider(LocalProvider provides ai.provider, LocalOpenGemini provides { prompt -> container.geminiPrompt = prompt; nav.navigate(GEMINI) }) {
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = route == LIBRARY,
                        onClick = { nav.navigate(LIBRARY) { popUpTo(LIBRARY) { inclusive = true } } },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = route == QUEUES,
                        onClick = { nav.navigate(QUEUES) { popUpTo(LIBRARY) } },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("Queue") },
                    )
                    NavigationBarItem(
                        selected = route == SETTINGS,
                        onClick = { nav.navigate(SETTINGS) { popUpTo(LIBRARY) } },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = LIBRARY, modifier = Modifier.padding(padding)) {
            composable(LIBRARY) {
                LibraryScreen(container, onOpenBook = { nav.navigate("reader/$it") })
            }
            composable(QUEUES) {
                QueueHomeScreen(container, onOpenQueue = { nav.navigate("queue/$it") })
            }
            composable(SETTINGS) { SettingsScreen(container) }
            composable(GEMINI) { GeminiWindowScreen(container, onBack = { nav.popBackStack() }) }
            composable(READER, arguments = listOf(navArgument("bookId") { type = NavType.LongType })) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                ReaderScreen(
                    container, bookId,
                    onBack = { nav.popBackStack() },
                    onTranslate = { block, page -> nav.navigate("translate/$bookId/$block/$page") },
                    // The reader is closed first so its own position save cannot overwrite a "go to section" jump made from the queue.
                    onQueue = { nav.navigate("queue/$bookId") { popUpTo(READER) { inclusive = true } } },
                    onSearch = { nav.navigate("tool/search/$bookId") },
                )
            }
            composable(QUEUE, arguments = listOf(navArgument("bookId") { type = NavType.LongType })) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                QueueScreen(
                    container, bookId,
                    onBack = { nav.popBackStack() },
                    onOpenJob = { job -> nav.navigate("translate/$bookId/-1/1?jobId=$job") },
                    onOpenReader = { nav.navigate("reader/$bookId") },
                    onTool = { kind -> nav.navigate("tool/$kind/$bookId") },
                )
            }
            composable(
                TOOL,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                val back: () -> Unit = { nav.popBackStack() }
                when (entry.arguments?.getString("kind")) {
                    "glossary" -> GlossaryScreen(container, bookId, back)
                    "summaries" -> SummariesScreen(container, bookId, back)
                    "verify" -> VerifyScreen(container, bookId, back, onCorrections = { nav.navigate("tool/corrections/$bookId") })
                    "corrections" -> CorrectionsScreen(container, bookId, back)
                    "export" -> ExportScreen(container, bookId, back)
                    // The reader is replaced so its own position save cannot overwrite the position chosen from a search result.
                    "search" -> SearchScreen(container, bookId, back, onOpenReader = { nav.navigate("reader/$bookId") { popUpTo(READER) { inclusive = true } } })
                }
            }
            composable(
                TRANSLATE,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("block") { type = NavType.IntType },
                    navArgument("page") { type = NavType.IntType },
                    navArgument("jobId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { entry ->
                val a = entry.arguments ?: return@composable
                val bookId = a.getLong("bookId")
                TranslateScreen(
                    container, bookId, a.getInt("block"), a.getInt("page"), a.getLong("jobId"),
                    onBack = { nav.popBackStack() },
                    // Move on to another batch without stacking screens: replace this one.
                    onOpenJob = { job -> nav.navigate("translate/$bookId/-1/1?jobId=$job") { popUpTo(TRANSLATE) { inclusive = true } } },
                )
            }
        }
    }
    }
}
