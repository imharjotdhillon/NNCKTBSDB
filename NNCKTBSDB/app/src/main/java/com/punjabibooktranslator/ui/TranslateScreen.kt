@file:OptIn(ExperimentalMaterial3Api::class)

package com.punjabibooktranslator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.SizeEstimator
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.core.TranslationStyle


@Composable
fun TranslateScreen(
    container: AppContainer, bookId: Long, startBlock: Int, startPage: Int, jobId: Long,
    onBack: () -> Unit, onOpenJob: (Long) -> Unit,
) {
    val vm = rememberVm("translate-$bookId-$jobId") { TranslateViewModel(container, bookId, startBlock, startPage, jobId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val openWindow = LocalOpenGemini.current
    val snackbar = rememberSnack(s.toast, vm::clearToast)
    BackHandler(enabled = s.stage != Stage.SETUP) { vm.back() }

    fun copy(text: String, what: String) {
        clipboard.setText(AnnotatedString(text))
        vm.toast("$what copied")
    }
    fun openGemini(text: String) = openWindow(text)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Translate with the AI") },
                navigationIcon = {
                    IconButton(onClick = { if (!vm.back()) onBack() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!s.ready) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (s.jobMissing) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("This batch no longer exists. It may have been removed from the queue.")
                    Button(onClick = onBack) { Text("Back to queue") }
                }
            } else when (s.stage) {
                Stage.SETUP -> SetupStage(s, vm)
                Stage.PROMPT -> PromptStage(
                    s, vm,
                    onCopy = { copy(s.prompt, "Prompt"); vm.markSent() },
                    onOpen = { openGemini(s.prompt); vm.markSent() },
                )
                Stage.PASTE -> PasteStage(
                    s, vm,
                    onPaste = {
                        val t = clipboard.getText()?.text
                        if (t.isNullOrBlank()) vm.toast("The clipboard is empty.") else vm.setPasted(t)
                    },
                    onCopyRepair = { s.repairPrompt?.let { copy(it, "Repair prompt") } },
                    onOpen = { openGemini(s.repairPrompt ?: s.prompt) },
                )
                Stage.REVIEW -> ReviewStage(s, vm, onCopyRepair = { s.repairPrompt?.let { copy(it, "Repair prompt") } }, onOpen = { openGemini(s.repairPrompt ?: s.prompt) })
                Stage.DONE -> DoneStage(s, vm, onBack, onOpenJob)
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SetupStage(s: TState, vm: TranslateViewModel) {
    val book = s.book ?: return
    val isPdf = book.format == "PDF"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(book.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "The app prepares a prompt. You send it to the AI yourself, then paste the AI's reply back here to review and save. " +
                "Nothing is sent automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Heading("Direction")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${TextUtils.languageName(s.sourceLang)} → ${TextUtils.languageName(s.targetLang)}" +
                if (s.targetLang == "pa") " (Gurmukhi)" else "", Modifier.weight(1f))
            OutlinedButton(onClick = vm::swapDirection) { Text("Swap") }
        }

        Heading("Style")
        ChipRow {
            for (st in TranslationStyle.entries) {
                FilterChip(selected = s.style == st, onClick = { vm.change { it.copy(style = st) } }, label = { Text(st.label) })
            }
        }
        Text(s.style.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.style != TranslationStyle.fromKey(book.translationStyle)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = s.saveStyleAsDefault, onCheckedChange = { c -> vm.change { it.copy(saveStyleAsDefault = c) } })
                Text("Save as this book's default style")
            }
        }

        Heading("What to translate")
        ChipRow {
            if (isPdf) {
                FilterChip(s.kind == ScopeKind.PAGES_FROM_HERE, { vm.change { it.copy(kind = ScopeKind.PAGES_FROM_HERE) } }, { Text("Pages from here") })
                FilterChip(s.kind == ScopeKind.PAGES_CUSTOM, { vm.change { it.copy(kind = ScopeKind.PAGES_CUSTOM) } }, { Text("Custom pages") })
            } else {
                FilterChip(s.kind == ScopeKind.CHAPTER, { vm.change { it.copy(kind = ScopeKind.CHAPTER) } }, { Text("Current chapter") })
                FilterChip(s.kind == ScopeKind.FROM_HERE_TEXT, { vm.change { it.copy(kind = ScopeKind.FROM_HERE_TEXT) } }, { Text("From here") })
            }
            FilterChip(s.kind == ScopeKind.PARAGRAPH_RANGE, { vm.change { it.copy(kind = ScopeKind.PARAGRAPH_RANGE) } }, { Text("Paragraph ids") })
        }
        when (s.kind) {
            ScopeKind.PAGES_FROM_HERE -> {
                ChipRow {
                    for (n in listOf(1, 5, 10, 20)) {
                        FilterChip(s.pageCount == n, { vm.change { it.copy(pageCount = n) } }, { Text(if (n == 1) "1 page" else "$n pages") })
                    }
                }
                Text("Starting at the page you were reading (page ${vm.startPageForDisplay}). Five pages is the default.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScopeKind.PAGES_CUSTOM -> RangeFields(s, vm, "First page", "Last page", numeric = true)
            ScopeKind.PARAGRAPH_RANGE -> RangeFields(s, vm, "First paragraph (e.g. P0012)", "Last paragraph", numeric = false)
            ScopeKind.FROM_HERE_TEXT -> {
                ChipRow {
                    for (n in listOf(5_000, 10_000, 20_000)) {
                        FilterChip(s.charBudget == n, { vm.change { it.copy(charBudget = n) } }, { Text("≈ %,d characters".format(n)) })
                    }
                }
                Text("EPUBs have no fixed pages, so the batch is sized by amount of text (about 10,000 characters is similar to five printed pages), starting at your reading position and ending on a paragraph boundary.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScopeKind.CHAPTER -> Text("The whole chapter you are in. Long chapters are split into parts automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.skipTranslated, { c -> vm.change { it.copy(skipTranslated = c) } })
            Spacer(Modifier.width(12.dp)); Text("Skip paragraphs that are already translated")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.includeContext, { c -> vm.change { it.copy(includeContext = c) } })
            Spacer(Modifier.width(12.dp)); Text("Include neighbouring paragraphs as context")
        }

        SummaryCard(s)
        Button(
            onClick = { vm.generatePrompt(0) },
            enabled = s.parts.isNotEmpty() && !s.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (s.parts.size > 1) "Generate prompt (part 1 of ${s.parts.size})" else "Generate prompt") }
    }
}

@Composable
private fun RangeFields(s: TState, vm: TranslateViewModel, a: String, b: String, numeric: Boolean) {
    val kb = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(s.customFrom, { v -> vm.change { it.copy(customFrom = v) } }, label = { Text(a) }, singleLine = true,
            keyboardOptions = kb, modifier = Modifier.weight(1f))
        OutlinedTextField(s.customTo, { v -> vm.change { it.copy(customTo = v) } }, label = { Text(b) }, singleLine = true,
            keyboardOptions = kb, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(s: TState) {
    Card(colors = tonalCardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val sel = s.selection
            if (sel == null) {
                Text(s.selectionError ?: "Working out the selection…", color = if (s.selectionError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(sel.label, style = MaterialTheme.typography.titleSmall)
                Text("${sel.blocks.size} paragraphs · ${sel.words} words · ${sel.chars} characters")
                if (sel.alreadyTranslated > 0) Text("${sel.alreadyTranslated} of ${sel.totalInRange} paragraphs in this range already have translations.",
                    style = MaterialTheme.typography.bodySmall)
                val outTokens = sel.blocks.sumOf { SizeEstimator.blockOutputTokens(com.punjabibooktranslator.core.PromptBlock(it.block.paraId, it.block.originalText), s.targetLang) }
                Text("Estimated reply size: about $outTokens tokens (conservative estimate)", style = MaterialTheme.typography.bodySmall)
                Text(if (s.includeContext) "Context: up to 2 paragraphs before and 1 after, plus the chapter title" else "Context: none (chapter title and book details only)",
                    style = MaterialTheme.typography.bodySmall)
                if (s.parts.size > 1) {
                    Text("This is too large to ask the AI for in one reply, so it is split into ${s.parts.size} parts. " +
                        "You will submit each part to the AI separately; nothing is cut off or skipped.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun PromptStage(s: TState, vm: TranslateViewModel, onCopy: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.job?.let { j ->
            Text(j.label, style = MaterialTheme.typography.titleSmall)
            Text("${s.selection?.blocks?.size ?: j.paraCount} paragraphs in this batch. It only counts as sent once you copy the prompt or open the AI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.parts.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.generatePrompt(s.partIndex - 1) }, enabled = s.partIndex > 0) { Text("◀") }
                Text("Part ${s.partIndex + 1} of ${s.parts.size}", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { vm.generatePrompt(s.partIndex + 1) }, enabled = s.partIndex < s.parts.size - 1) { Text("▶") }
            }
        }
        Text("1. Copy the prompt   2. Open the AI and paste it   3. Send   4. Copy the AI's whole reply   5. Come back and tap “I have the reply”.",
            style = MaterialTheme.typography.bodyMedium)
        s.promptWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val ctx = buildList {
            add(if (s.glossaryUsed > 0) "${s.glossaryUsed} glossary term${if (s.glossaryUsed == 1) "" else "s"}" else "no glossary terms")
            if (s.examplesUsed > 0) add("${s.examplesUsed} approved example${if (s.examplesUsed == 1) "" else "s"}")
            if (s.hasSummary) add("chapter summary")
        }
        Text("Included in this prompt: ${ctx.joinToString(", ")}." +
            (if (s.glossaryOmitted > 0) " ${s.glossaryOmitted} more matching term(s) were left out to keep the prompt short." else ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy prompt") }
            FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open ${LocalProvider.current.label}") }
        }
        Button(onClick = vm::goToPaste, modifier = Modifier.fillMaxWidth()) { Text("I have the reply →") }
        Text("Preview (${s.prompt.length} characters)", style = MaterialTheme.typography.labelLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            SelectionContainer {
                Text(
                    if (s.prompt.length > 3000) s.prompt.take(3000) + "\n\n… preview shortened here. “Copy prompt” copies all ${s.prompt.length} characters." else s.prompt,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun PasteStage(s: TState, vm: TranslateViewModel, onPaste: () -> Unit, onCopyRepair: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paste the AI's reply here", style = MaterialTheme.typography.titleMedium)
        Text("The clipboard is only read when you tap “Paste from clipboard”.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = s.pasted, onValueChange = vm::setPasted,
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
            minLines = 10, label = { Text("The AI's reply") },
        )
        Text("${s.pasted.length} characters", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onPaste, modifier = Modifier.weight(1f)) { Text("Paste from clipboard") }
            OutlinedButton(onClick = { vm.setPasted("") }, enabled = s.pasted.isNotEmpty()) { Text("Clear") }
        }
        Button(onClick = vm::parseResponse, enabled = s.pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Read the reply") }

        s.parse?.error?.let { err ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Your pasted text is kept above; nothing was discarded. You can ask the AI to reformat it without changing the translation:",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (s.repairPrompt != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onCopyRepair, modifier = Modifier.weight(1f)) { Text("Copy repair prompt") }
                            FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open ${LocalProvider.current.label}") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewStage(s: TState, vm: TranslateViewModel, onCopyRepair: () -> Unit, onOpen: () -> Unit) {
    val r = s.parse
    var confirm by remember { mutableStateOf(false) }
    val blank = s.review.count { it.text.isBlank() }
    val savable = s.review.size - blank
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = tonalCardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Review before saving", style = MaterialTheme.typography.titleMedium)
                        Text("$savable of ${s.review.size} paragraphs have a translation. Edit anything, then save. Nothing is saved until you tap Save.")
                        r?.warnings?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                        if (r != null && r.duplicateIds.isNotEmpty()) Text("• Duplicates: ${r.duplicateIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        if (s.repairPrompt != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = onCopyRepair) { Text("Copy repair prompt") }
                                OutlinedButton(onClick = onOpen) { Text("Open ${LocalProvider.current.label}") }
                            }
                        }
                    }
                }
            }
            items(s.review, key = { it.row.block.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.row.block.paraId, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            item.row.block.pdfPage?.let { Text("  ·  page $it", style = MaterialTheme.typography.labelSmall) }
                        }
                        SelectionContainer { Text(item.row.block.originalText, style = MaterialTheme.typography.bodyMedium) }
                        item.flag?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(
                            value = item.text, onValueChange = { vm.editReview(item.row.block.id, it) },
                            label = { Text("Translation") }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (s.unknown.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Entries the AI returned that don't match any requested paragraph (not saved):", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            s.unknown.forEach {
                                SelectionContainer { Text("${it.id}: ${it.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = { if (blank > 0) confirm = true else vm.saveReview() },
            enabled = savable > 0 && !s.busy,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Save $savable translation${if (savable == 1) "" else "s"}") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Some paragraphs are empty") },
            text = { Text("$blank paragraph${if (blank == 1) " has" else "s have"} no translation. They will stay untranslated and can be done later. Save the other $savable now?") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.saveReview() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun DoneStage(s: TState, vm: TranslateViewModel, onBack: () -> Unit, onOpenJob: (Long) -> Unit) {
    val inJob = s.jobId >= 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Saved", style = MaterialTheme.typography.headlineSmall)
        Text(s.doneInfo.orEmpty())
        if (inJob) {
            s.nextJobId?.let { next -> Button(onClick = { onOpenJob(next) }, modifier = Modifier.fillMaxWidth()) { Text("Next batch") } }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to queue") }
        } else {
            val remaining = s.selection
            if (remaining != null && s.parts.isNotEmpty()) {
                Text("${remaining.blocks.size} paragraph${if (remaining.blocks.size == 1) "" else "s"} in this selection still need translating" +
                    if (s.parts.size > 1) " (${s.parts.size} parts)." else ".")
                Button(onClick = { vm.generatePrompt(0) }, modifier = Modifier.fillMaxWidth()) { Text("Prepare the next prompt") }
            }
            OutlinedButton(onClick = { vm.goTo(Stage.SETUP) }, modifier = Modifier.fillMaxWidth()) { Text("Choose another selection") }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to reading") }
        }
    }
}
