package com.punjabibooktranslator.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.ExportAudit
import com.punjabibooktranslator.core.ExportMode
import com.punjabibooktranslator.core.PlanResult
import com.punjabibooktranslator.data.BookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.net.Uri

data class EState(
    val book: BookEntity? = null,
    val audit: ExportAudit? = null,
    val mode: ExportMode = ExportMode.TRANSLATED,
    val allowIncomplete: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class ExportViewModel(private val c: AppContainer, private val bookId: Long) : ViewModel() {
    private val _s = MutableStateFlow(EState())
    val state: StateFlow<EState> = _s.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val b = c.db.bookDao().getById(bookId)
            _s.update { it.copy(book = b, audit = c.exports.audit(bookId)) }
        }
    }

    fun setMode(m: ExportMode) = _s.update { it.copy(mode = m) }
    fun setAllow(v: Boolean) = _s.update { it.copy(allowIncomplete = v) }
    fun clearMessage() = _s.update { it.copy(message = null) }

    /** Re-reads the database at the moment of export, so the file reflects what is saved now, not what the screen showed earlier. */
    fun export(uri: Uri, pdf: Boolean) {
        val s = _s.value
        val book = s.book ?: return
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            val msg = when (val r = c.exports.plan(book, s.mode, s.allowIncomplete)) {
                is PlanResult.Refused -> "Not exported: ${r.reason}"
                is PlanResult.Ready -> {
                    val err = if (pdf) c.exports.writePdf(r.doc, uri) else c.exports.writeEpub(book, r.doc, uri)
                    err ?: "Exported ${r.doc.audit.translated} of ${r.doc.audit.total} paragraphs" +
                        (if (r.doc.audit.isComplete) "." else " (${r.doc.audit.missing.size} marked “Not translated yet”).")
                }
            }
            _s.update { it.copy(busy = false, message = msg, audit = c.exports.audit(bookId)) }
        }
    }
}

private fun fileName(b: BookEntity, mode: ExportMode, ext: String): String {
    val safe = b.title.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "book" }
    return "$safe-${b.targetLang}-${if (mode == ExportMode.BILINGUAL) "bilingual" else "translation"}.$ext"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(container: AppContainer, bookId: Long, onBack: () -> Unit) {
    val vm = rememberVm("export-$bookId") { ExportViewModel(container, bookId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnack(s.message, vm::clearMessage)
    val epub = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { it?.let { u -> vm.export(u, false) } }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let { u -> vm.export(u, true) } }
    val book = s.book
    val a = s.audit

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Bar("Export", onBack) },
    ) { padding ->
        if (book == null || a == null) {
            Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = tonalCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall)
                    Text("${a.translated} of ${a.total} paragraphs translated (${a.percent}%)")
                    if (a.missing.isNotEmpty()) Text("${a.missing.size} not translated yet, for example ${a.missing.take(5).joinToString()}.", color = MaterialTheme.colorScheme.error)
                    if (a.noTextBlocks > 0) Text("${a.noTextBlocks} page(s) have no selectable text and are left out (no OCR yet).", style = MaterialTheme.typography.bodySmall)
                    if (a.unresolved > 0) Text("${a.unresolved} translated paragraph(s) still have unresolved verification issues.", style = MaterialTheme.typography.bodySmall)
                    Text("${a.unverified} translated paragraph(s) have not been verified. Export does not require verification.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val modes = ExportMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, m -> SegmentedButton(selected = s.mode == m, onClick = { vm.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(m.label) } }
            }
            if (!a.isComplete) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.allowIncomplete, vm::setAllow)
                    Text("Export anyway. Untranslated paragraphs show “[Not translated yet]”; nothing is invented.", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            val can = !s.busy && a.translated > 0 && (a.isComplete || s.allowIncomplete)
            Button(onClick = { epub.launch(fileName(book, s.mode, "epub")) }, enabled = can, modifier = Modifier.fillMaxWidth()) { Text("Export as EPUB") }
            Button(onClick = { pdf.launch(fileName(book, s.mode, "pdf")) }, enabled = can, modifier = Modifier.fillMaxWidth()) { Text("Export as PDF") }
            OutlinedButton(onClick = vm::refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh counts") }
            if (s.busy) CircularProgressIndicator()
            Text(
                "The Gurmukhi font is embedded in both formats. The PDF contains selectable text. Check the first export on your device: " +
                    "Gurmukhi shaping in the PDF has not been verified yet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
