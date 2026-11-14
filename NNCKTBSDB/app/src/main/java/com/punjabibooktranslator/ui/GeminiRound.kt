package com.punjabibooktranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The manual AI step shared by verification and summaries: copy a prompt, open the AI yourself, paste the reply back.
 * Nothing is sent or read automatically; the clipboard is read only when "Paste from clipboard" is tapped.
 */
@Composable
fun GeminiRound(
    prompt: String,
    pasted: String,
    onPasted: (String) -> Unit,
    readLabel: String,
    onRead: () -> Unit,
    onMessage: (String) -> Unit,
    onCopied: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val openWindow = LocalOpenGemini.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("1. Copy the prompt   2. Open the AI, paste it and send   3. Copy the AI's whole reply   4. Paste it below.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(prompt)); onCopied(); onMessage("Prompt copied") }, modifier = Modifier.weight(1f)) { Text("Copy prompt") }
            FilledTonalButton(
                onClick = { openWindow(prompt) },
                modifier = Modifier.weight(1f),
            ) { Text("Open ${LocalProvider.current.label}") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            SelectionContainer {
                Text(
                    if (prompt.length > 1500) prompt.take(1500) + "\n\n… preview shortened. “Copy prompt” copies all ${prompt.length} characters." else prompt,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp),
                )
            }
        }
        OutlinedTextField(
            value = pasted, onValueChange = onPasted, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            minLines = 6, label = { Text("The AI's reply") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { clipboard.getText()?.text?.let(onPasted) ?: onMessage("The clipboard is empty.") }, modifier = Modifier.weight(1f)) { Text("Paste from clipboard") }
            OutlinedButton(onClick = { onPasted("") }, enabled = pasted.isNotEmpty()) { Text("Clear") }
        }
        Button(onClick = onRead, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(readLabel) }
    }
}
