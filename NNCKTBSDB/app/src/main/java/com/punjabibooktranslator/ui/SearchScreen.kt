package com.punjabibooktranslator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.SearchText
import com.punjabibooktranslator.core.Snippets
import com.punjabibooktranslator.data.ReadingProgressEntity
import com.punjabibooktranslator.data.SearchRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun highlight(text: String, query: String): AnnotatedString {
    val sn = Snippets.around(text, query)
    return buildAnnotatedString {
        if (sn.matchStart < 0) { append(sn.text); return@buildAnnotatedString }
        append(sn.text.substring(0, sn.matchStart))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(sn.text.substring(sn.matchStart, sn.matchEnd)) }
        append(sn.text.substring(sn.matchEnd))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(container: AppContainer, bookId: Long, onBack: () -> Unit, onOpenReader: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchRow>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); searched = false; return@LaunchedEffect }
        delay(250) // let the user finish typing; the effect restarts on every keystroke
        results = container.context.search(bookId, query)
        searched = true
    }

    Scaffold(
        topBar = {
            Bar("Search this book", onBack)
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
                label = { Text("Original or translated text") },
                supportingText = { Text("Searches both languages. English matching ignores case; Gurmukhi matches exactly as typed.") },
            )
            if (searched && results.isEmpty()) {
                Text("Nothing found.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (results.size >= 200) item { Text("Showing the first 200 matches. Type more to narrow it down.", style = MaterialTheme.typography.bodySmall) }
                items(results, key = { it.blockId }) { r ->
                    val inOriginal = SearchText.indexOfIgnoreCase(r.originalText, query.trim()) >= 0
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val dao = container.db.readerDao()
                                val old = dao.getProgress(bookId)
                                dao.upsertProgress(
                                    ReadingProgressEntity(
                                        bookId = bookId, lastBlockIndex = r.textOrder, lastPdfPage = r.pdfPage ?: old?.lastPdfPage ?: 1,
                                        displayMode = if (inOriginal || r.translatedText == null) "ORIGINAL" else "TRANSLATED",
                                        viewMode = "TEXT", lastOpenedAt = System.currentTimeMillis(),
                                    )
                                )
                                onOpenReader()
                            }
                        },
                        colors = tonalCardColors(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${r.paraId}${r.pdfPage?.let { " · page $it" } ?: ""} · ${if (inOriginal) "original" else "translation"}", style = MaterialTheme.typography.labelSmall)
                            Text(highlight(if (inOriginal) r.originalText else r.translatedText.orEmpty(), query), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
