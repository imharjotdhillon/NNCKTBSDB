package com.punjabibooktranslator.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.punjabibooktranslator.AppContainer
import kotlinx.coroutines.launch

/** Screens call this with the text the user is about to send (a prompt); AppRoot opens the in-app AI window. */
val LocalOpenGemini = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The chosen AI chat website (Gemini by default) shown inside the app. It is a plain browser view: the app does NOT inject scripts, read the page,
 * fill the prompt box, press Send, or read the reply, and it never touches cookies or sign-in data (Android's WebView keeps its
 * own session like any browser). The user pastes, sends, and copies the reply themselves. The prompt is only placed on the
 * clipboard when the user taps "Copy prompt".
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GeminiWindowScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val prompt = container.geminiPrompt
    val provider = LocalProvider.current

    BackHandler(enabled = canGoBack) { web?.goBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(provider.label) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Copy the prompt, paste it into the chat box (long-press) and send. When the reply is finished, use the site's copy button, then tap Back and “Paste from clipboard”.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { clipboard.setText(AnnotatedString(prompt)); scope.launch { snackbar.showSnackbar("Prompt copied") } },
                            enabled = prompt.isNotEmpty(), modifier = Modifier.weight(1f),
                        ) { Text("Copy prompt") }
                        OutlinedButton(
                            onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.url))) }
                                catch (e: Exception) { scope.launch { snackbar.showSnackbar("No browser found.") } }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Open in browser") }
                    }
                    Text(
                        "If the site refuses to sign in here, use “Open in browser”; the manual copy and paste works the same way.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true      // the AI's own page needs it; the app injects no script of its own
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (provider.allows(uri.scheme, uri.host)) return false
                            // Anything outside the provider's own and sign-in domains opens in the user's own browser instead of inside the app.
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { /* nothing can open it */ }
                            return true
                        }
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { canGoBack = view.canGoBack() }
                    }
                    loadUrl(provider.url)
                    web = this
                }
            },
            onRelease = { it.stopLoading(); it.destroy(); web = null },
        )
    }
}
