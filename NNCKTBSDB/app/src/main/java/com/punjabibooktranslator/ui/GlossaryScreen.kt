package com.punjabibooktranslator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.GlossaryType
import com.punjabibooktranslator.core.OccResult
import com.punjabibooktranslator.core.TermCandidate
import com.punjabibooktranslator.core.TermChecker
import com.punjabibooktranslator.data.GlossaryEntity
import com.punjabibooktranslator.data.GlossaryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GState(
    val checking: GlossaryEntity? = null,
    val results: List<OccResult> = emptyList(),
    val suggestions: List<TermCandidate>? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class GlossaryViewModel(private val c: AppContainer, private val bookId: Long) : ViewModel() {
    val terms: StateFlow<List<GlossaryEntity>?> = c.glossary.observe(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _s = MutableStateFlow(GState())
    val state: StateFlow<GState> = _s.asStateFlow()

    fun clearMessage() = _s.update { it.copy(message = null) }

    fun save(id: Long?, term: String, translation: String, type: GlossaryType, explanation: String, approved: Boolean, done: () -> Unit) {
        viewModelScope.launch {
            val r = if (id == null) c.glossary.add(bookId, term, translation, type, explanation, approved)
            else c.glossary.update(id, term, translation, type, explanation, approved)
            when (r) {
                GlossaryResult.SAVED -> { done(); _s.update { it.copy(message = "Saved") } }
                GlossaryResult.DUPLICATE -> _s.update { it.copy(message = "This term is already in the glossary.") }
                GlossaryResult.INVALID -> _s.update { it.copy(message = "Both the term and its translation are needed.") }
            }
        }
    }

    fun delete(id: Long) = viewModelScope.launch { c.glossary.delete(id) }

    fun check(t: GlossaryEntity) {
        viewModelScope.launch {
            _s.update { it.copy(busy = true, checking = t) }
            val r = c.glossary.occurrences(t)
            _s.update { it.copy(busy = false, results = r) }
        }
    }

    fun closeCheck() = _s.update { it.copy(checking = null, results = emptyList()) }

    fun flag() {
        val r = _s.value.results
        viewModelScope.launch {
            val n = c.glossary.flagForReview(r)
            _s.update { it.copy(message = if (n == 0) "Nothing needed flagging." else "$n paragraph(s) marked “Needs review”. Their text was not changed.") }
        }
    }

    fun suggest() {
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val r = c.glossary.suggest(bookId)
            _s.update { it.copy(busy = false, suggestions = r) }
        }
    }

    fun closeSuggestions() = _s.update { it.copy(suggestions = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(container: AppContainer, bookId: Long, onBack: () -> Unit) {
    val vm = rememberVm("glossary-$bookId") { GlossaryViewModel(container, bookId) }
    val terms by vm.terms.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnack(s.message, vm::clearMessage)
    var editor by remember { mutableStateOf<GlossaryEntity?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }   // non-null = add dialog open, value = prefilled term
    var filter by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<GlossaryEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Bar("Glossary", onBack) { TextButton(onClick = vm::suggest, enabled = !s.busy) { Text("Suggest names") } }
        },
        floatingActionButton = { FloatingActionButton(onClick = { adding = "" }) { Icon(Icons.Default.Add, contentDescription = "Add term") } },
    ) { padding ->
        val list = terms
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Only terms marked “Approved” are sent to the AI, and only when they appear in the text being translated. Gurmukhi terms match as typed.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("Filter terms") })
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            } else if (list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No terms yet. Add character names, places or phrases whose translation should stay the same everywhere.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val shown = list.filter { filter.isBlank() || it.term.contains(filter, true) || it.translation.contains(filter, true) }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { t ->
                        Card(Modifier.fillMaxWidth().clickable { editor = t }, colors = tonalCardColors()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${t.term}  →  ${t.translation}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    GlossaryType.fromKey(t.type).label + (if (t.approved) "" else " · Draft (not sent to the AI)") +
                                        (t.explanation?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { vm.check(t) }) { Text("Where it appears") }
                                    TextButton(onClick = { confirmDelete = t }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding != null || editor != null) {
        TermDialog(
            existing = editor, initialTerm = adding.orEmpty(),
            onSave = { term, tr, type, ex, ap -> vm.save(editor?.id, term, tr, type, ex, ap) { editor = null; adding = null } },
            onDismiss = { editor = null; adding = null },
        )
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${t.term}”?") },
            text = { Text("Translations already saved are not changed. The term just stops being used in future prompts.") },
            confirmButton = { TextButton(onClick = { vm.delete(t.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    s.checking?.let { t -> OccurrenceDialog(t, s, onClose = vm::closeCheck, onFlag = vm::flag) }
    s.suggestions?.let { sug ->
        AlertDialog(
            onDismissRequest = vm::closeSuggestions,
            title = { Text("Recurring names") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (sug.isEmpty()) Text("No recurring capitalised names found. This only works for Latin-script (English) text; for Gurmukhi, add terms by hand.")
                    else Text("Tap a name to add it. These are guesses from capital letters, so ignore any that are not names.", style = MaterialTheme.typography.bodySmall)
                    sug.forEach { c -> TextButton(onClick = { vm.closeSuggestions(); adding = c.term }) { Text("${c.term}  (${c.count}×)") } }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeSuggestions) { Text("Close") } },
        )
    }
}

@Composable
private fun TermDialog(
    existing: GlossaryEntity?, initialTerm: String,
    onSave: (String, String, GlossaryType, String, Boolean) -> Unit, onDismiss: () -> Unit,
) {
    var term by remember { mutableStateOf(existing?.term ?: initialTerm) }
    var tr by remember { mutableStateOf(existing?.translation ?: "") }
    var type by remember { mutableStateOf(GlossaryType.fromKey(existing?.type)) }
    var ex by remember { mutableStateOf(existing?.explanation ?: "") }
    var approved by remember { mutableStateOf(existing?.approved ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add term" else "Edit term") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(term, { term = it }, Modifier.fillMaxWidth(), label = { Text("Term (in the source text)") }, singleLine = true)
                OutlinedTextField(tr, { tr = it }, Modifier.fillMaxWidth(), label = { Text("Approved translation") }, singleLine = true)
                Text("Type", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GlossaryType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
                }
                OutlinedTextField(ex, { ex = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(approved, { approved = it })
                    Text("Approved: send to the AI when this term appears", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (existing != null && tr.trim() != existing.translation) {
                    Text("Changing the translation does not change saved paragraphs. Use “Where it appears” afterwards to find and flag the ones that still use the old rendering.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(term, tr, type, ex, approved) }, enabled = term.isNotBlank() && tr.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OccurrenceDialog(t: GlossaryEntity, s: GState, onClose: () -> Unit, onFlag: () -> Unit) {
    val bad = s.results.count { TermChecker.needsReview(it.state) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("“${t.term}” in this book") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.busy) Text("Searching…")
                else if (s.results.isEmpty()) Text("This term was not found in the original text.")
                else {
                    Text("${s.results.size} paragraph(s) contain it. $bad need a look.", style = MaterialTheme.typography.titleSmall)
                    s.results.take(80).forEach { r ->
                        Text("${r.row.paraId}${r.row.page?.let { " · p.$it" } ?: ""} — ${TermChecker.label(r.state)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (TermChecker.needsReview(r.state)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    if (s.results.size > 80) Text("…and ${s.results.size - 80} more", style = MaterialTheme.typography.bodySmall)
                    Text("Only the original text was searched; the check looks for the approved translation inside each saved translation.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { if (bad > 0) Button(onClick = onFlag) { Text("Mark $bad as Needs review") } else TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { if (bad > 0) TextButton(onClick = onClose) { Text("Close") } },
    )
}
