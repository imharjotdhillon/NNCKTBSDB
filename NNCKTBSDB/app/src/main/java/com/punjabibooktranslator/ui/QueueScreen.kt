@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.punjabibooktranslator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.BookProgress
import com.punjabibooktranslator.core.JobRules
import com.punjabibooktranslator.core.JobStatus
import com.punjabibooktranslator.core.ProgressCalc
import com.punjabibooktranslator.core.QueueGroup
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.core.UnitAgg
import com.punjabibooktranslator.data.ImportStatus
import com.punjabibooktranslator.data.JobView

/** Queue tab: pick which book's queue to open. */
@Composable
fun QueueHomeScreen(container: AppContainer, onOpenQueue: (Long) -> Unit) {
    val rows by container.db.bookDao().observeLibrary().collectAsStateWithLifecycle(initialValue = emptyList())
    val counts by container.db.jobDao().observeOpenCounts().collectAsStateWithLifecycle(initialValue = emptyList())
    val byBook = counts.associateBy { it.bookId }
    val ready = rows.filter { it.book.importStatus == ImportStatus.READY }
    Scaffold(topBar = { Bar("Translation queue") }) { padding ->
        if (ready.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Import a book first. Its translation queue will appear here.", Modifier.padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ready.sortedByDescending { it.lastOpenedAt ?: it.book.importedAt }, key = { it.book.id }) { r ->
                    val oc = byBook[r.book.id]
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenQueue(r.book.id) },
                        colors = tonalCardColors(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Translated ${ProgressCalc.percent(r.translatedBlocks, r.totalBlocks)}% · ${r.totalBlocks - r.translatedBlocks} paragraphs left",
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (oc == null) "No open batches" else "${oc.open} open batch${if (oc.open == 1) "" else "es"}" +
                                    if (oc.attention > 0) " · ${oc.attention} need your attention" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (oc != null && oc.attention > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueueScreen(
    container: AppContainer,
    bookId: Long,
    onBack: () -> Unit,
    onOpenJob: (Long) -> Unit,
    onOpenReader: () -> Unit,
    onTool: (String) -> Unit,
) {
    val vm = rememberVm("queue-$bookId") { QueueViewModel(container, bookId) }
    val book by vm.book.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val planner by vm.planner.collectAsStateWithLifecycle()
    val corrections by container.review.observeOpenCount(bookId).collectAsStateWithLifecycle(initialValue = 0)
    val snackbar = rememberSnack(vm.messages)

    var confirmRemove by remember { mutableStateOf<JobView?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showIncomplete by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }

    val b = book
    val isPdf = b?.format == "PDF"
    fun goTo(u: UnitAgg, translated: Boolean) = vm.prepareReader(u.firstOrder, if (isPdf) u.unitId.toInt() else null, translated, onOpenReader)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(b?.title ?: "Queue", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (b == null || progress == null || jobs == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val p = progress!!
        val list = jobs!!
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ProgressCard(p, if (isPdf) "Pages" else "Chapters", showIncomplete, showReview,
                onToggleIncomplete = { showIncomplete = !showIncomplete }, onToggleReview = { showReview = !showReview }) }
            if (showIncomplete) {
                items(p.incompleteUnits.take(60), key = { "i${it.unitId}" }) { u -> UnitRow(u) { goTo(u, false) } }
                if (p.incompleteUnits.size > 60) item { Text("…and ${p.incompleteUnits.size - 60} more", style = MaterialTheme.typography.bodySmall) }
            }
            if (showReview) {
                items(p.reviewUnits.take(60), key = { "r${it.unitId}" }) { u -> UnitRow(u, reviewNote = true) { goTo(u, true) } }
                if (p.reviewUnits.size > 60) item { Text("…and ${p.reviewUnits.size - 60} more", style = MaterialTheme.typography.bodySmall) }
            }

            item {
                Card(colors = tonalCardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tools", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { onTool("glossary") }) { Text("Glossary") }
                            OutlinedButton(onClick = { onTool("summaries") }) { Text("Chapter summaries") }
                            OutlinedButton(onClick = { onTool("verify") }) { Text("Verify with AI") }
                            OutlinedButton(onClick = { onTool("corrections") }) { Text(if (corrections > 0) "Corrections ($corrections)" else "Corrections") }
                            OutlinedButton(onClick = { onTool("search") }) { Text("Search") }
                            OutlinedButton(onClick = { onTool("export") }) { Text("Export") }
                        }
                    }
                }
            }
            item { PlannerCard(b.format == "PDF", planner, vm::plan, onAdd = vm::addToQueue) }

            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::pauseAll, enabled = list.any { it.status == JobStatus.PENDING }) { Text("Pause preparation") }
                    OutlinedButton(onClick = vm::resumeAll, enabled = list.any { it.status == JobStatus.PAUSED }) { Text("Resume") }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = list.any { it.status == JobStatus.PENDING || it.status == JobStatus.PAUSED }) { Text("Clear planned") }
                }
            }

            if (list.isEmpty()) {
                item {
                    Text("The queue is empty. Choose a batch size above and tap “Add batches to queue”. Nothing is sent anywhere: " +
                        "you copy each prompt into the AI yourself and paste the reply back.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            for (g in JobRules.groupOrder) {
                val inGroup = list.filter { JobRules.group(it.status) == g }
                if (inGroup.isEmpty()) continue
                item(key = "h-${g.name}") { GroupHeader(g, inGroup.size) }
                items(inGroup, key = { it.job.id }) { jv ->
                    JobCard(
                        jv,
                        onOpen = { onOpenJob(jv.job.id) },
                        onPause = { vm.pause(jv.job.id) }, onResume = { vm.resume(jv.job.id) },
                        onCancel = { vm.cancel(jv.job.id) },
                        onRetry = { vm.retry(jv.job.id) { onOpenJob(jv.job.id) } },
                        onRemove = { if (JobRules.needsConfirmToRemove(jv.status)) confirmRemove = jv else vm.remove(jv.job.id) },
                        onGoTo = { vm.prepareReader(jv.job.firstOrder, jv.job.firstPage, jv.translated > 0, onOpenReader) },
                    )
                }
            }
        }
    }

    confirmRemove?.let { jv ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove this batch?") },
            text = { Text("The pasted reply stored with this batch will be discarded. Translations you already saved are not affected.") },
            confirmButton = { TextButton(onClick = { vm.remove(jv.job.id); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear planned batches?") },
            text = { Text("Removes batches that are only planned or paused (nothing was sent for them). Batches you have started, and all saved translations, are kept.") },
            confirmButton = { TextButton(onClick = { vm.clearPlanned(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProgressCard(
    p: BookProgress, unitName: String, showIncomplete: Boolean, showReview: Boolean,
    onToggleIncomplete: () -> Unit, onToggleReview: () -> Unit,
) {
    Card(colors = tonalCardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Translation progress: ${p.percent}%", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${p.translatedBlocks} of ${p.totalBlocks} paragraphs translated · ${p.missingBlocks} missing", style = MaterialTheme.typography.bodyMedium)
            Text("$unitName: ${p.totalUnits} total · ${p.fullUnits} fully translated · ${p.partialUnits} partly · ${p.untranslatedUnits} untranslated",
                style = MaterialTheme.typography.bodySmall)
            Text("Awaiting review: ${p.needsReviewBlocks} paragraphs (${p.unitsAwaitingReview} ${unitName.lowercase()}) · Verified: ${p.verifiedBlocks} paragraphs",
                style = MaterialTheme.typography.bodySmall)
            Text("The percentage counts paragraphs, not ${unitName.lowercase()}: a page with 10 of 12 paragraphs translated is not complete. " +
                "${unitName} with no readable text (scanned) are not counted.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleIncomplete, enabled = p.incompleteUnits.isNotEmpty()) {
                    Text((if (showIncomplete) "Hide" else "Show") + " incomplete (${p.incompleteUnits.size})")
                }
                OutlinedButton(onClick = onToggleReview, enabled = p.reviewUnits.isNotEmpty()) {
                    Text((if (showReview) "Hide" else "Show") + " needs review (${p.reviewUnits.size})")
                }
            }
        }
    }
}

@Composable
private fun UnitRow(u: UnitAgg, reviewNote: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(u.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (reviewNote) "${u.needsReview} to review" else "${u.translated}/${u.total} translated",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PlannerCard(isPdf: Boolean, s: PlannerState, change: ((PlannerState) -> PlannerState) -> Unit, onAdd: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plan batches", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (isPdf) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf(1, 5, 10, 20)) {
                        FilterChip(s.pagesPerBatch == n, { change { it.copy(pagesPerBatch = n) } }, { Text(if (n == 1) "1 page" else "$n pages") })
                    }
                    FilterChip(s.pagesPerBatch == -1, { change { it.copy(pagesPerBatch = -1) } }, { Text("Custom") })
                }
                if (s.pagesPerBatch == -1) {
                    OutlinedTextField(s.customSize, { v -> change { it.copy(customSize = v.filter(Char::isDigit)) } }, label = { Text("Pages per batch") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(s.fromPage, { v -> change { it.copy(fromPage = v.filter(Char::isDigit)) } }, label = { Text("From page (blank = 1)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(s.toPage, { v -> change { it.copy(toPage = v.filter(Char::isDigit)) } }, label = { Text("To page (blank = end)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Text("Five pages is the default. A batch whose text is too long for one AI reply is split into parts automatically.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf(5_000, 10_000, 20_000)) {
                        FilterChip(s.charBudget == n, { change { it.copy(charBudget = n) } }, { Text("≈ %,d characters".format(n)) })
                    }
                    FilterChip(s.charBudget == -1, { change { it.copy(charBudget = -1) } }, { Text("Custom") })
                }
                if (s.charBudget == -1) {
                    OutlinedTextField(s.customChars, { v -> change { it.copy(customChars = v.filter(Char::isDigit)) } }, label = { Text("Characters per batch") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Text("EPUBs have no fixed pages, so batches are sized by amount of text (about 10,000 characters is similar to five printed pages). " +
                    "Each batch ends on a paragraph boundary and tends to stop at chapter ends.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.skipTranslated, { c -> change { it.copy(skipTranslated = c) } })
                Text("Skip paragraphs already translated", Modifier.padding(start = 12.dp))
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add batches to queue") }
        }
    }
}

@Composable
private fun GroupHeader(g: QueueGroup, count: Int) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("${g.title} ($count)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(g.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JobCard(
    jv: JobView,
    onOpen: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit,
    onRetry: () -> Unit, onRemove: () -> Unit, onGoTo: () -> Unit,
) {
    val j = jv.job
    val status = jv.status
    val problem = status == JobStatus.FAILED || status == JobStatus.INCOMPLETE
    Card(colors = tonalCardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(j.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${j.paraCount} paragraphs · ${jv.translated} saved · about %,d characters".format(j.chars) +
                "  ·  ${TextUtils.languageName(j.sourceLang)} → ${TextUtils.languageName(j.targetLang)}",
                style = MaterialTheme.typography.bodySmall)
            Text(JobRules.statusText(status, jv.missing, j.lastError), style = MaterialTheme.typography.bodySmall,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (status) {
                    JobStatus.PENDING -> { TextButton(onClick = onOpen) { Text("Prepare prompt") }; TextButton(onClick = onPause) { Text("Pause") } }
                    JobStatus.PAUSED -> TextButton(onClick = onResume) { Text("Resume") }
                    JobStatus.PREPARED -> { TextButton(onClick = onOpen) { Text("Open prompt") }; TextButton(onClick = onPause) { Text("Pause") } }
                    JobStatus.AWAITING_RESPONSE -> TextButton(onClick = onOpen) { Text("Paste reply") }
                    JobStatus.REVIEW -> TextButton(onClick = onOpen) { Text("Review") }
                    JobStatus.FAILED -> { TextButton(onClick = onOpen) { Text("Open reply") }; TextButton(onClick = onRetry) { Text("Retry") } }
                    JobStatus.INCOMPLETE -> TextButton(onClick = onRetry) { Text("Retry missing") }
                    JobStatus.CANCELLED -> TextButton(onClick = onRetry) { Text("Retry") }
                }
                if (JobRules.canCancel(status)) TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onGoTo) { Text("Go to text") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}
