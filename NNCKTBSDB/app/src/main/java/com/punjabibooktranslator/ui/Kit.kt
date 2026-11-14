package com.punjabibooktranslator.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow

/** A ViewModel built by hand (no DI framework), kept per [key] across recompositions and rotation. */
@Composable
inline fun <reified VM : ViewModel> rememberVm(key: String, noinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/** A snackbar host that shows [message] once, then calls [onShown] so the state can clear it. */
@Composable
fun rememberSnack(message: String?, onShown: () -> Unit): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { host.showSnackbar(it); onShown() } }
    return host
}

/** A snackbar host fed by a stream of one-off messages. */
@Composable
fun rememberSnack(messages: Flow<String>): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { messages.collect { host.showSnackbar(it) } }
    return host
}

/** The standard top bar: a title, an optional back arrow, optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { if (onBack != null) BackButton(onBack) },
        actions = actions,
    )
}

/** The AI chat the user chose in Settings. Provided once at the top of the app. */
val LocalProvider = androidx.compose.runtime.staticCompositionLocalOf { com.punjabibooktranslator.core.AiProvider.GEMINI }
