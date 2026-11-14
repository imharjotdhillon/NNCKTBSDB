package com.punjabibooktranslator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.SummaryParser
import com.punjabibooktranslator.core.SummaryPlanner
import com.punjabibooktranslator.core.SummaryPromptBuilder
import com.punjabibooktranslator.data.BlockStatus
import com.punjabibooktranslator.data.BookEntity
import com.punjabibooktranslator.data.ChapterSummaryRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SState(
    val book: BookEntity? = null,
    val chapter: ChapterSummaryRow? = null,
    val draft: String = "",
    val parts: List<List<String>> = emptyList(),
    val partIndex: Int = 0,
    val prompt: String? = null,
    val pasted: String = "",
    val error: String? = null,
    val notes: List<String> = emptyList(),
    val busy: Boolean = false,
    val toast: String? = null,
)

class SummariesViewModel(private val c: AppContainer, private val bookId: Long) : ViewModel() {
    val chapters: StateFlow<List<ChapterSummaryRow>?> = c.context.observeSummaries(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _s = MutableStateFlow(SState())
    val state: StateFlow<SState> = _s.asStateFlow()

    init { viewModelScope.launch { _s.update { it.copy(book = c.db.bookDao().getById(bookId)) } } }

    fun open(ch: ChapterSummaryRow) = _s.update { SState(book = it.book, chapter = ch, draft = ch.summary.orEmpty()) }
    fun close() = _s.update { SState(book = it.book) }
    fun setDraft(t: String) = _s.update { it.copy(draft = t) }
    fun setPasted(t: String) = _s.update { it.copy(pasted = t, error = null, notes = emptyList()) }
    fun toast(m: String) = _s.update { it.copy(toast = m) }
    fun clearToast() = _s.update { it.copy(toast = null) }

    fun save() {
        val s = _s.value
        val ch = s.chapter ?: return
        viewModelScope.launch {
            c.context.setSummary(ch.id, s.draft)
            _s.update { it.copy(toast = "Summary saved") }
        }
    }

    /** Splits the chapter's original text into parts so even a long chapter is summarised in several prompts, never cut. */
    fun startGenerate() {
        val s = _s.value
        val ch = s.chapter ?: return
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val texts = c.context.chapterBlocks(bookId, ch.id).filter { it.block.extractionStatus == BlockStatus.OK }.map { it.block.originalText }
            if (texts.isEmpty()) { _s.update { it.copy(busy = false, error = "This chapter has no text to summarise.") }; return@launch }
            val parts = SummaryPlanner.split(texts, { it.length + 2 }, budget = 20_000)
            _s.update { it.copy(parts = parts, busy = false) }
            buildPrompt(0)
        }
    }

    fun buildPrompt(index: Int) {
        val s = _s.value
        val b = s.book ?: return
        val ch = s.chapter ?: return
        val part = s.parts.getOrNull(index) ?: return
        val prompt = SummaryPromptBuilder.build(b.title, b.author, ch.title, b.sourceLang, part, index, s.parts.size, if (index == 0) null else s.draft)
        _s.update { it.copy(partIndex = index, prompt = prompt, pasted = "", error = null, notes = emptyList()) }
    }

    /** Puts the AI's summary into the editable box. It is only stored when the user taps Save. */
    fun read() {
        val r = SummaryParser.parse(_s.value.pasted)
        if (r.error != null) _s.update { it.copy(error = r.error) }
        else _s.update { it.copy(draft = r.text, error = null, notes = r.warnings, pasted = "") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummariesScreen(container: AppContainer, bookId: Long, onBack: () -> Unit) {
    val vm = rememberVm("summaries-$bookId") { SummariesViewModel(container, bookId) }
    val list by vm.chapters.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnack(s.toast, vm::clearToast)
    BackHandler(enabled = s.chapter != null) { vm.close() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Bar(s.chapter?.title ?: "Chapter summaries", { if (s.chapter != null) vm.close() else onBack() })
        },
    ) { padding ->
        val ch = s.chapter
        if (ch == null) {
            val l = list.orEmpty()
            if (l.isEmpty()) {
                Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text("This book has no chapters to summarise.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text("A short summary gives the AI the story so far when it translates a chapter. It is background only and never replaces the text.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(l, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth().clickable { vm.open(r) }, colors = tonalCardColors()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                Text(if (r.summary.isNullOrBlank()) "No summary yet · ${r.paraCount} paragraphs" else "Summary saved · ${r.paraCount} paragraphs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (r.summary.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(s.draft, vm::setDraft, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("Summary (you can write or edit it yourself)") }, minLines = 6)
                Text("${s.draft.length} characters", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::save) { Text("Save summary") }
                    OutlinedButton(onClick = vm::startGenerate, enabled = !s.busy) { Text("Ask the AI to write it") }
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                s.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                val p = s.prompt
                if (p != null) {
                    if (s.parts.size > 1) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedButton(onClick = { vm.buildPrompt(s.partIndex - 1) }, enabled = s.partIndex > 0) { Text("◀") }
                            Text("Part ${s.partIndex + 1} of ${s.parts.size}", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { vm.buildPrompt(s.partIndex + 1) }, enabled = s.partIndex < s.parts.size - 1) { Text("▶") }
                        }
                        Text("Do the parts in order. Each later part asks the AI to update the summary above, so the draft must hold the result of the previous part first.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    GeminiRound(prompt = p, pasted = s.pasted, onPasted = vm::setPasted, readLabel = "Use this summary", onRead = vm::read, onMessage = vm::toast)
                }
            }
        }
    }
}
