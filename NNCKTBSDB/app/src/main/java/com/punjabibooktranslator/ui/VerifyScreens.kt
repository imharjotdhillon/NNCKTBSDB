package com.punjabibooktranslator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.Fingerprint
import com.punjabibooktranslator.core.GlossaryMatcher
import com.punjabibooktranslator.core.PromptBlock
import com.punjabibooktranslator.core.Resolve
import com.punjabibooktranslator.core.Severity
import com.punjabibooktranslator.core.SummaryPlanner
import com.punjabibooktranslator.core.VerificationParser
import com.punjabibooktranslator.core.VerifyItem
import com.punjabibooktranslator.core.VerifyParse
import com.punjabibooktranslator.core.VerifyPromptBuilder
import com.punjabibooktranslator.core.VerifySpec
import com.punjabibooktranslator.data.BlockRow
import com.punjabibooktranslator.data.BlockStatus
import com.punjabibooktranslator.data.BookEntity
import com.punjabibooktranslator.data.ChapterRow
import com.punjabibooktranslator.data.FindingRow
import com.punjabibooktranslator.data.VerifyApplied
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ verification

enum class VStage { SCOPE, PROMPT, RESULT }

data class VState(
    val book: BookEntity? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val chapterId: Long? = null,
    val fromText: String = "",
    val toText: String = "",
    val stage: VStage = VStage.SCOPE,
    val parts: List<List<BlockRow>> = emptyList(),
    val skippedUntranslated: Int = 0,
    val partIndex: Int = 0,
    val prompt: String = "",
    val fingerprint: String = "",
    val pasted: String = "",
    val parse: VerifyParse? = null,
    val repairPrompt: String? = null,
    val applied: VerifyApplied? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val toast: String? = null,
)

class VerifyViewModel(private val c: AppContainer, private val bookId: Long) : ViewModel() {
    private val _s = MutableStateFlow(VState())
    val state: StateFlow<VState> = _s.asStateFlow()
    private val dao = c.db.readerDao()

    init {
        viewModelScope.launch {
            val b = c.db.bookDao().getById(bookId)
            val ch = dao.observeChapters(bookId).first()
            _s.update { it.copy(book = b, chapters = ch) }
        }
    }

    fun update(f: (VState) -> VState) = _s.update(f)
    fun toast(m: String) = _s.update { it.copy(toast = m) }
    fun clearToast() = _s.update { it.copy(toast = null) }

    /** Collects the already-translated paragraphs of the chosen scope and splits them into prompt-sized parts. */
    fun prepare() {
        val s = _s.value
        viewModelScope.launch {
            _s.update { it.copy(busy = true, error = null) }
            val rows: List<BlockRow> = if (s.chapterId != null) {
                dao.blocksByChapter(bookId, s.chapterId)
            } else {
                val from = s.fromText.trim().toIntOrNull()
                val to = s.toText.trim().toIntOrNull()
                if (from == null || to == null || from < 1 || to < from) {
                    _s.update { it.copy(busy = false, error = "Enter a paragraph range such as 1 to 20, or pick a chapter.") }
                    return@launch
                }
                dao.blocksByOrder(bookId, from - 1, to - 1)
            }
            val ok = rows.filter { it.block.extractionStatus == BlockStatus.OK }
            val translated = ok.filter { it.translatedText != null }
            if (translated.isEmpty()) {
                _s.update { it.copy(busy = false, error = "Nothing in this selection has been translated yet, so there is nothing to verify.") }
                return@launch
            }
            val parts = SummaryPlanner.split(translated, { it.block.originalText.length + (it.translatedText?.length ?: 0) + 80 }, budget = 12_000)
            _s.update { it.copy(parts = parts, skippedUntranslated = ok.size - translated.size, busy = false) }
            generate(0)
        }
    }

    fun generate(index: Int) {
        val s = _s.value
        val b = s.book ?: return
        val part = s.parts.getOrNull(index) ?: return
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val first = part.first().block
            val last = part.last().block
            val before = dao.blocksByOrder(bookId, maxOf(0, first.textOrder - 1), first.textOrder - 1)
            val after = dao.blocksByOrder(bookId, last.textOrder + 1, last.textOrder + 1)
            val rel = c.glossary.relevantFor(bookId, part.map { it.block.originalText } + part.mapNotNull { it.translatedText })
            val spec = VerifySpec(
                bookTitle = b.title, author = b.author, chapterTitle = part.first().chapterTitle,
                locationLabel = "paragraphs ${first.paraId}–${last.paraId}", sourceLang = b.sourceLang, targetLang = b.targetLang,
                items = part.map { VerifyItem(it.block.paraId, it.block.originalText, it.translatedText.orEmpty()) },
                contextBefore = before.map { PromptBlock(it.block.paraId, it.block.originalText.take(500)) },
                contextAfter = after.map { PromptBlock(it.block.paraId, it.block.originalText.take(500)) },
                chapterSummary = first.chapterId?.let { dao.chapterSummary(it) }?.takeIf { it.isNotBlank() },
                glossary = GlossaryMatcher.promptLines(rel.terms),
            )
            val prompt = VerifyPromptBuilder.build(spec)
            val fp = Fingerprint.encode(part.associate { it.block.id to it.translatedText.orEmpty() })
            _s.update { it.copy(stage = VStage.PROMPT, partIndex = index, prompt = prompt, fingerprint = fp, pasted = "", parse = null, repairPrompt = null, applied = null, busy = false) }
        }
    }

    fun read() {
        val s = _s.value
        val part = s.parts.getOrNull(s.partIndex) ?: return
        val ids = part.map { it.block.paraId }
        val p = VerificationParser.parse(s.pasted, ids)
        val repair = if (p.error != null) VerifyPromptBuilder.buildRepair(ids, s.pasted, p.error) else null
        _s.update { it.copy(parse = p, repairPrompt = repair, stage = if (p.error == null) VStage.RESULT else it.stage) }
    }

    fun apply() {
        val s = _s.value
        val p = s.parse ?: return
        val part = s.parts.getOrNull(s.partIndex) ?: return
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val r = c.review.applyVerification(part.map { it.block.id }, p, s.fingerprint)
            _s.update { it.copy(applied = r, busy = false) }
        }
    }

    fun back(): Boolean {
        val s = _s.value
        return when (s.stage) {
            VStage.SCOPE -> false
            VStage.PROMPT -> { _s.update { it.copy(stage = VStage.SCOPE) }; true }
            VStage.RESULT -> { _s.update { it.copy(stage = VStage.PROMPT, parse = null) }; true }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(container: AppContainer, bookId: Long, onBack: () -> Unit, onCorrections: () -> Unit) {
    val vm = rememberVm("verify-$bookId") { VerifyViewModel(container, bookId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnack(s.toast, vm::clearToast)
    BackHandler(enabled = s.stage != VStage.SCOPE) { vm.back() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Bar("Verify with AI", { if (!vm.back()) onBack() })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (s.stage) {
                VStage.SCOPE -> {
                    Text("Ask the AI to check translations you already saved. It reports problems; nothing changes until you accept a correction.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("A paragraph is only marked “Verified” when the AI's pasted reply says it is fine. Paragraphs the reply does not mention stay “Not verified”.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.chapters.isNotEmpty()) {
                        Text("A chapter", style = MaterialTheme.typography.labelLarge)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.chapters.take(40).forEach { ch ->
                                FilterChip(selected = s.chapterId == ch.id, onClick = { vm.update { it.copy(chapterId = if (it.chapterId == ch.id) null else ch.id) } },
                                    label = { Text(ch.title, maxLines = 1) })
                            }
                        }
                    }
                    Text("…or a paragraph range (paragraph numbers, P0001 is 1)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(s.fromText, { t -> vm.update { it.copy(fromText = t.filter(Char::isDigit), chapterId = null) } }, Modifier.weight(1f),
                            label = { Text("From") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(s.toText, { t -> vm.update { it.copy(toText = t.filter(Char::isDigit), chapterId = null) } }, Modifier.weight(1f),
                            label = { Text("To") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = vm::prepare, enabled = !s.busy && (s.chapterId != null || (s.fromText.isNotBlank() && s.toText.isNotBlank())), modifier = Modifier.fillMaxWidth()) {
                        Text("Prepare verification prompt")
                    }
                    OutlinedButton(onClick = onCorrections, modifier = Modifier.fillMaxWidth()) { Text("Open pending corrections") }
                    if (s.busy) CircularProgressIndicator()
                }
                VStage.PROMPT -> {
                    if (s.parts.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { vm.generate(s.partIndex - 1) }, enabled = s.partIndex > 0) { Text("◀") }
                            Text("Part ${s.partIndex + 1} of ${s.parts.size}", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { vm.generate(s.partIndex + 1) }, enabled = s.partIndex < s.parts.size - 1) { Text("▶") }
                        }
                    }
                    Text("${s.parts.getOrNull(s.partIndex)?.size ?: 0} paragraphs in this prompt.", style = MaterialTheme.typography.bodyMedium)
                    if (s.skippedUntranslated > 0) {
                        Text("${s.skippedUntranslated} paragraph(s) in the selection are not translated yet and were left out.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("This check is not saved if you close the app; if that happens, prepare it again.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GeminiRound(
                        prompt = s.prompt, pasted = s.pasted, onPasted = { t -> vm.update { it.copy(pasted = t, parse = null, repairPrompt = null) } },
                        readLabel = "Read the reply", onRead = vm::read, onMessage = vm::toast,
                    )
                    s.parse?.error?.let { err ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text("Your pasted text is kept. You can ask the AI to reformat it without changing its findings:", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                s.repairPrompt?.let { rp -> GeminiRepair(rp, vm::toast) }
                            }
                        }
                    }
                }
                VStage.RESULT -> {
                    val p = s.parse
                    if (p != null) {
                        Text("What the AI said", style = MaterialTheme.typography.titleMedium)
                        Text("${p.okCount} paragraph(s) judged fine · ${p.issueCount} with issues · ${p.notCovered.size} not covered (they stay “Not verified”).")
                        if (p.truncated) Text("The reply looks cut off. Only complete findings were read.", color = MaterialTheme.colorScheme.error)
                        p.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        val a = s.applied
                        if (a == null) {
                            Button(onClick = vm::apply, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) { Text("Save these results") }
                            Text("Saving records states and findings only. No translation text is changed.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Saved: ${a.verified} verified, ${a.withIssues} with issues.", style = MaterialTheme.typography.titleSmall)
                            if (a.stale.isNotEmpty()) Text("${a.stale.size} paragraph(s) were edited after the prompt was made and were skipped: ${a.stale.take(10).joinToString()}.", color = MaterialTheme.colorScheme.error)
                            if (a.notCovered > 0) Text("${a.notCovered} paragraph(s) had no result and remain not verified.")
                            if (a.withIssues > 0) Button(onClick = onCorrections, modifier = Modifier.fillMaxWidth()) { Text("Review ${a.withIssues} correction(s)") }
                            if (s.partIndex < s.parts.size - 1) Button(onClick = { vm.generate(s.partIndex + 1) }, modifier = Modifier.fillMaxWidth()) { Text("Next part") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiRepair(prompt: String, onMessage: (String) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val openWindow = LocalOpenGemini.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(prompt)); onMessage("Repair prompt copied") }) { Text("Copy repair prompt") }
        OutlinedButton(onClick = { openWindow(prompt) }) { Text("Open ${LocalProvider.current.label}") }
    }
}

// ------------------------------------------------------------------ corrections

class CorrectionsViewModel(private val c: AppContainer, bookId: Long) : ViewModel() {
    val findings: StateFlow<List<FindingRow>?> =
        c.review.observeOpenFindings(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _msg = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _msg.asStateFlow()
    fun clearMessage() { _msg.value = null }

    fun resolve(id: Long, action: String, edited: String? = null) {
        viewModelScope.launch {
            val ok = c.review.resolve(id, action, edited)
            _msg.value = if (ok) when (action) { Resolve.ACCEPT -> "Correction applied"; Resolve.EDIT -> "Your edit was saved"; else -> "Kept your translation" }
            else "That could not be applied (nothing was changed)."
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionsScreen(container: AppContainer, bookId: Long, onBack: () -> Unit) {
    val vm = rememberVm("corr-$bookId") { CorrectionsViewModel(container, bookId) }
    val list by vm.findings.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val snackbar = rememberSnack(msg, vm::clearMessage)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Bar("Pending corrections", onBack)
        },
    ) { padding ->
        val l = list
        when {
            l == null -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
            l.isEmpty() -> Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("No pending corrections. Findings from a the AI verification appear here until you decide on each one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(l, key = { it.finding.id }) { f -> FindingCard(f, vm) }
            }
        }
    }
}

@Composable
private fun FindingCard(f: FindingRow, vm: CorrectionsViewModel) {
    var editing by remember(f.finding.id) { mutableStateOf(false) }
    var text by remember(f.finding.id) { mutableStateOf(f.finding.suggestion ?: f.translatedText) }
    val sev = Severity.parse(f.finding.severity)
    Card(colors = tonalCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${f.paraId} · ${sev?.label ?: f.finding.severity}", style = MaterialTheme.typography.titleSmall,
                color = if (sev == Severity.MAJOR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(f.finding.issueText)
            f.finding.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Original", style = MaterialTheme.typography.labelMedium); Text(f.originalText, style = MaterialTheme.typography.bodySmall)
            Text("Current translation", style = MaterialTheme.typography.labelMedium); Text(f.translatedText)
            f.finding.suggestion?.let { Text("The AI suggests", style = MaterialTheme.typography.labelMedium); Text(it) }
                ?: Text("The AI gave no replacement text for this one.", style = MaterialTheme.typography.bodySmall)
            if (editing) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Your version") }, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.resolve(f.finding.id, Resolve.EDIT, text) }, enabled = text.isNotBlank()) { Text("Save my edit") }
                    OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f.finding.suggestion != null) Button(onClick = { vm.resolve(f.finding.id, Resolve.ACCEPT) }) { Text("Accept") }
                    OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                    OutlinedButton(onClick = { vm.resolve(f.finding.id, Resolve.REJECT) }) { Text("Keep mine") }
                }
            }
        }
    }
}
