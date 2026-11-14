@file:OptIn(ExperimentalMaterial3Api::class)

package com.punjabibooktranslator.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.data.ImportStatus
import com.punjabibooktranslator.data.LibraryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(container: AppContainer, onOpenBook: (Long) -> Unit) {
    val vm = rememberVm("library") { LibraryViewModel(container) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val grid by vm.grid.collectAsStateWithLifecycle()

    val snackbar = rememberSnack(vm.messages)

    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<LibraryRow?>(null) }
    var confirmDelete by remember { mutableStateOf<LibraryRow?>(null) }
    var failedRow by remember { mutableStateOf<LibraryRow?>(null) }

    val context = LocalContext.current
    fun persistRead(uri: Uri?) {
        uri ?: return
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not every provider offers persistable permission; we keep our own copy anyway.
        }
        vm.import(uri)
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { persistRead(it) }
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { persistRead(it) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = vm::setQuery, singleLine = true,
                            placeholder = { Text("Search title or author") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text("Library")
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { vm.toggleGrid() }) {
                        Icon(Icons.Default.Menu, contentDescription = if (grid) "Switch to list" else "Switch to grid")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for (o in SortOrder.entries) {
                                DropdownMenuItem(
                                    text = { Text((if (o == sort) "✓ " else "") + o.label) },
                                    onClick = { vm.setSort(o); sortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { importMenu = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Import") },
                )
                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                    DropdownMenuItem(text = { Text("Import PDF") }, onClick = {
                        importMenu = false; pdfPicker.launch(arrayOf("application/pdf"))
                    })
                    DropdownMenuItem(text = { Text("Import EPUB") }, onClick = {
                        importMenu = false; epubPicker.launch(arrayOf("application/epub+zip"))
                    })
                }
            }
        },
    ) { padding ->
        val list = rows
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                list == null -> {}
                list.isEmpty() -> Text(
                    if (query.isNotBlank()) "No books match “$query”." else "No books yet.\nTap Import to add a PDF or EPUB.",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val resume = list.filter { it.lastOpenedAt != null && it.book.importStatus == ImportStatus.READY }
                        .maxByOrNull { it.lastOpenedAt ?: 0L }
                    val onClick: (LibraryRow) -> Unit = { r ->
                        when (r.book.importStatus) {
                            ImportStatus.READY -> onOpenBook(r.book.id)
                            ImportStatus.FAILED -> failedRow = r
                            else -> {}
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        if (resume != null && query.isBlank()) {
                            TextButton(
                                onClick = { onOpenBook(resume.book.id) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) { Text("Resume reading: ${resume.book.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        if (grid) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(160.dp),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(list, key = { it.book.id }) { r ->
                                    BookCard(r, grid = true, onClick = { onClick(r) }, onDetails = { details = r }, onDelete = { confirmDelete = r })
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(list, key = { it.book.id }) { r ->
                                    BookCard(r, grid = false, onClick = { onClick(r) }, onDetails = { details = r }, onDelete = { confirmDelete = r })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    details?.let { r ->
        AlertDialog(
            onDismissRequest = { details = null },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } },
            title = { Text(r.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { BookDetails(r) },
        )
    }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this book?") },
            text = { Text("“${r.book.title}” and all of its saved translations, bookmarks and reading progress will be permanently removed from this device.") },
            confirmButton = { TextButton(onClick = { vm.delete(r.book.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    failedRow?.let { r ->
        AlertDialog(
            onDismissRequest = { failedRow = null },
            title = { Text("Import problem") },
            text = { Text(r.book.importError ?: "This book could not be imported.") },
            confirmButton = { TextButton(onClick = { vm.retry(r.book.id); failedRow = null }) { Text("Retry") } },
            dismissButton = {
                TextButton(onClick = { confirmDelete = r; failedRow = null }) { Text("Remove") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(row: LibraryRow, grid: Boolean, onClick: () -> Unit, onDetails: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val b = row.book
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        colors = tonalCardColors(),
    ) {
        val content: @Composable () -> Unit = {
            CoverImage(
                b.coverPath, b.format,
                if (grid) Modifier.fillMaxWidth().aspectRatio(0.7f) else Modifier.width(72.dp).aspectRatio(0.7f),
            )
            Column(Modifier.padding(12.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                b.author?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                when (b.importStatus) {
                    ImportStatus.IMPORTING -> {
                        Text("Importing… ${b.importProgress}%", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { b.importProgress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    ImportStatus.FAILED -> Text("Import failed — tap for options", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text("${b.format} · ${TextUtils.languageName(b.sourceLang)} → ${TextUtils.languageName(b.targetLang)}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val read = if (row.allBlocks > 0 && row.lastBlockIndex != null) (row.lastBlockIndex + 1) * 100 / row.allBlocks else 0
                        val tr = com.punjabibooktranslator.core.ProgressCalc.percent(row.translatedBlocks, row.totalBlocks)
                        LinearProgressIndicator(progress = { tr / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text("Translated $tr% · Read $read%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (b.noTextPages > 0) Text("${b.noTextPages} pages without text", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                        if (!File(b.localPath).exists()) Text("Original file unavailable", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (grid) Column { content() } else Row(verticalAlignment = Alignment.Top) { content() }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) },
                onClick = { menu = false; onDetails() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun BookDetails(r: LibraryRow) {
    val b = r.book
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        b.author?.let { Text("Author: $it") }
        Text("Format: ${b.format}   Size: ${"%.1f".format(b.fileSize / 1_048_576.0)} MB")
        Text("Language: ${TextUtils.languageName(b.sourceLang)} → ${TextUtils.languageName(b.targetLang)}")
        if (b.pageCount > 0) Text("Pages: ${b.pageCount} (${b.noTextPages} without selectable text)")
        Text("Text blocks: ${r.allBlocks}")
        Text("Imported: ${fmt.format(Date(b.importedAt))}")
        r.lastOpenedAt?.let { Text("Last opened: ${fmt.format(Date(it))}") }
        Text("Stored copy: " + if (File(b.localPath).exists()) "present" else "MISSING")
    }
}

@Composable
fun CoverImage(path: String?, format: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { decodeSampled(p, 400)?.asImageBitmap() } }
    }
    Box(modifier.then(Modifier), contentAlignment = Alignment.Center) {
        val img = bmp
        if (img != null) {
            Image(img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(format, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun decodeSampled(path: String, reqWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
