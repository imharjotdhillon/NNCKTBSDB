package com.punjabibooktranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.data.BlockRow
import com.punjabibooktranslator.data.RevisionEntity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Edit one saved translation. The original text is shown for reference and is never editable. Every save keeps the
 * previous text as a revision, so an edit can always be undone from "Earlier versions".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParagraphEditorSheet(row: BlockRow, vm: ReaderViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(row.block.id) { mutableStateOf(row.translatedText.orEmpty()) }
    var revisions by remember(row.block.id) { mutableStateOf<List<RevisionEntity>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val blank = TextUtils.normalizeTranslation(text).isEmpty()
    val unchanged = TextUtils.normalizeTranslation(text) == row.translatedText.orEmpty()

    LaunchedEffect(row.block.id, showHistory) { if (showHistory) revisions = vm.revisions(row.block.id) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit translation · ${row.block.paraId}", style = MaterialTheme.typography.titleMedium)
            Text("Original (cannot be edited)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(row.block.originalText, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = text, onValueChange = { text = it; message = null },
                label = { Text("Translation") }, minLines = 4, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                isError = blank, supportingText = { if (blank) Text("A translation cannot be saved empty.") },
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !blank && !unchanged,
                    onClick = { scope.launch { if (vm.saveEdit(row.block.id, text)) onDismiss() else message = "Nothing was changed." } },
                ) { Text("Save edit") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
            TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide earlier versions" else "Earlier versions") }
            if (showHistory) {
                if (revisions.isEmpty()) Text("No earlier versions yet. One is kept each time this translation changes.", style = MaterialTheme.typography.bodySmall)
                revisions.forEach { r ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Version ${r.revision} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.createdAt))}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(r.translatedText, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { scope.launch { if (vm.revertTo(row.block.id, r.id)) onDismiss() } }) { Text("Restore this version") }
                    }
                }
            }
        }
    }
}
