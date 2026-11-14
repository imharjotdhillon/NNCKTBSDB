@file:OptIn(ExperimentalMaterial3Api::class)

package com.punjabibooktranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import com.punjabibooktranslator.core.AiProvider
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.data.ReaderPrefs
import com.punjabibooktranslator.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val prefs by container.prefs.prefs.collectAsStateWithLifecycle(initialValue = ReaderPrefs())
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { Bar("Settings") }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section("Appearance") {
                ThemeModeSelector(prefs.themeMode) { mode -> scope.launch { container.prefs.update { copy(themeMode = mode) } } }
                Text(
                    "Font size, spacing, margins and brightness are changed from the reader's settings button.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section("AI chat") {
                Text("Which website the in-app window opens. The workflow is the same for all of them: you paste the prompt, send it, and paste the reply back.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiProvider.entries.forEach { p ->
                        FilterChip(selected = prefs.provider == p, onClick = { scope.launch { container.prefs.update { copy(provider = p) } } }, label = { Text(p.label) })
                    }
                }
                Text("Only Gemini was part of the original design. The others use the same copy-and-paste steps but are untested here, each site's own terms and " +
                    "privacy settings apply to what you paste, and some sites refuse sign-in inside an app window (use “Open in browser” then). " +
                    "Prompts and the JSON reply format are written for any capable chat model; weaker models may need the repair prompt more often.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("Privacy") {
                Text(
                    "Your books, extracted text and reading progress are stored only on this device. The app makes no network requests " +
                        "of its own and uploads nothing. Internet access exists only for the in-app AI window, which shows the " +
                        "chat website you chose like a browser; text reaches the AI only when you paste it there yourself. Backups and exports are " +
                        "files you choose where to save.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Section("Backup") {
                BackupSection(container)
            }
            Section("About") {
                Text("Punjabi Book Translator 0.5.0 (reader, translation, queue, glossary, verification, export, backup)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Open-source components: Noto Sans Gurmukhi (SIL Open Font License 1.1), Jsoup (MIT), " +
                        "PDFBox-Android (Apache 2.0), AndroidX and Jetpack Compose (Apache 2.0). " +
                        "The font license text is bundled in the app's assets.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
fun ThemeModeSelector(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun BackupSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var overwrite by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { busy = true; message = container.backup.write(uri) ?: "Backup saved."; busy = false }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { busy = true; report = container.backup.restore(uri, overwrite); busy = false }
    }
    Text(
        "A backup file holds your translations, glossary and chapter summaries for every imported book. It does not contain the books " +
            "themselves. To restore on another device, import the same book files first, then restore.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { create.launch("punjabi-book-translator-backup.json") }, enabled = !busy) { Text("Create backup") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(overwrite, { overwrite = it })
        Text("When restoring, replace translations that differ (old text is kept in history)", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("Restore from backup") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    report?.let { lines ->
        AlertDialog(
            onDismissRequest = { report = null }, title = { Text("Restore result") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { lines.forEach { Text(it) } } },
            confirmButton = { TextButton(onClick = { report = null }) { Text("Close") } },
        )
    }
}
