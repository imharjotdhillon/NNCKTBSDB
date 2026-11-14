@file:OptIn(ExperimentalMaterial3Api::class)

package com.punjabibooktranslator.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.R
import com.punjabibooktranslator.core.VerifyStates
import com.punjabibooktranslator.data.BlockRow
import com.punjabibooktranslator.data.BlockStatus
import com.punjabibooktranslator.data.BookEntity
import com.punjabibooktranslator.data.BookmarkEntity
import com.punjabibooktranslator.data.ChapterRow
import com.punjabibooktranslator.data.ReaderFont
import com.punjabibooktranslator.data.ReaderPrefs
import com.punjabibooktranslator.data.ThemeMode
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val GurmukhiFamily = FontFamily(
    Font(R.font.noto_sans_gurmukhi_regular, FontWeight.Normal),
    Font(R.font.noto_sans_gurmukhi_bold, FontWeight.Bold),
)

private fun fontFamilyFor(font: ReaderFont): FontFamily = when (font) {
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.GURMUKHI -> GurmukhiFamily
}

/** A one-shot request to scroll the active reader to [index] (a block index or a 0-based page index). */
private data class ReaderJump(val index: Int, val token: Long = System.nanoTime())

private val InvertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
)

@Composable
fun ReaderScreen(
    container: AppContainer, bookId: Long, onBack: () -> Unit, onTranslate: (block: Int, page: Int) -> Unit, onQueue: () -> Unit,
    onSearch: () -> Unit,
) {
    val vm = rememberVm("reader-$bookId") { ReaderViewModel(container, bookId) }
    val book by vm.book.collectAsStateWithLifecycle()
    val initial by vm.initial.collectAsStateWithLifecycle()
    val b = book
    val init = initial
    if (b == null || init == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        ReaderContent(vm, b, init, onTranslate, onQueue, onSearch)
    }
}

@Composable
private fun ReaderContent(
    vm: ReaderViewModel, book: BookEntity, init: ReaderInitial, onTranslate: (Int, Int) -> Unit, onQueue: () -> Unit, onSearch: () -> Unit,
) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val total by vm.blockCount.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val items = vm.pages.collectAsLazyPagingItems()
    val isPdf = book.format == "PDF"
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var displayMode by rememberSaveable { mutableStateOf(init.displayMode) }
    var viewMode by rememberSaveable { mutableStateOf(init.viewMode) }
    var textIndex by rememberSaveable { mutableStateOf(init.blockIndex) }
    var currentPage by rememberSaveable { mutableStateOf(init.pdfPage) }
    var textStart by rememberSaveable { mutableStateOf(init.blockIndex) }
    var pagesStart by rememberSaveable { mutableStateOf((init.pdfPage - 1).coerceAtLeast(0)) }
    var chapterTitle by remember { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf<ReaderJump?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showContents by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BlockRow?>(null) }

    // Screen brightness override for the reading screen only.
    val activity = LocalContext.current as? Activity
    DisposableEffect(prefs.brightness) {
        activity?.window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = if (prefs.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else prefs.brightness
            w.attributes = lp
        }
        onDispose {
            activity?.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    fun switchView(target: String) {
        if (target == viewMode) return
        if (target == "PAGES") {
            pagesStart = (currentPage - 1).coerceAtLeast(0)
            viewMode = "PAGES"
            vm.setViewMode("PAGES")
        } else {
            scope.launch {
                val idx = vm.blockIndexForPage(currentPage) ?: textIndex
                textStart = idx
                textIndex = idx
                viewMode = "TEXT"
                vm.setViewMode("TEXT")
            }
        }
    }

    fun goTo(blockIndex: Int?, page: Int?) {
        if (viewMode == "PAGES") {
            if (page != null) jump = ReaderJump(page - 1)
        } else if (blockIndex != null) {
            jump = ReaderJump(blockIndex)
        } else if (page != null) {
            scope.launch { vm.blockIndexForPage(page)?.let { jump = ReaderJump(it) } }
        }
    }

    val positionLine = if (isPdf) {
        "Page $currentPage of ${book.pageCount}"
    } else {
        listOfNotNull(chapterTitle, if (total > 0) "${(textIndex + 1) * 100 / total}%" else null).joinToString(" · ")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // Light/dark toggle: top-left, as specified.
                navigationIcon = {
                    IconButton(onClick = { vm.updatePrefs { copy(themeMode = if (dark) ThemeMode.LIGHT else ThemeMode.DARK) } }) {
                        ThemeIcon(dark, contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode")
                    }
                },
                title = {
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (positionLine.isNotEmpty()) {
                            Text(positionLine, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val label = if (isPdf) "Page $currentPage" else (chapterTitle ?: "Position ${textIndex + 1}")
                        vm.addBookmark(label, textIndex, if (isPdf) currentPage else null, viewMode == "PAGES")
                        scope.launch { snackbar.showSnackbar("Bookmarked: $label") }
                    }) { Icon(Icons.Default.Star, contentDescription = "Add bookmark") }
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search this book") }
                    IconButton(onClick = { showContents = true }) { Icon(Icons.Default.Menu, contentDescription = "Contents and bookmarks") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Reader settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (viewMode == "TEXT") {
                    val modes = listOf("ORIGINAL" to "Original", "TRANSLATED" to "Translated")
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        modes.forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = displayMode == key,
                                onClick = { displayMode = key; vm.setDisplayMode(key) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(label) }
                        }
                    }
                } else {
                    Text("Page images show the original PDF.", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { onTranslate(if (viewMode == "PAGES") -1 else textIndex, currentPage) }) { Text("Translate") }
            }
            if (isPdf) {
                val views = listOf("TEXT" to "Text", "PAGES" to "Pages")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    views.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = viewMode == key,
                            onClick = { switchView(key) },
                            shape = SegmentedButtonDefaults.itemShape(i, views.size),
                        ) { Text(label) }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (isPdf && viewMode == "PAGES") {
                    PagesReader(
                        vm = vm, pageCount = book.pageCount, start = pagesStart, dark = dark,
                        jump = jump, onJumpConsumed = { jump = null },
                        onPage = { p -> currentPage = p + 1; vm.reportPage(p + 1) },
                    )
                } else {
                    TextReader(
                        pagingItems = items, start = textStart, translated = displayMode == "TRANSLATED",
                        prefs = prefs, isPdf = isPdf, jump = jump, onJumpConsumed = { jump = null },
                        onEdit = { editing = it },
                        onPosition = { idx, page, title ->
                            textIndex = idx
                            if (page != null) currentPage = page
                            if (title != null) chapterTitle = title
                            vm.reportText(idx, page)
                        },
                    )
                }
            }
        }
    }

    editing?.let { row -> ParagraphEditorSheet(row, vm, onDismiss = { editing = null }) }
    if (showSettings) {
        ReaderSettingsSheet(prefs, onChange = vm::updatePrefs, onDismiss = { showSettings = false })
    }
    if (showContents) {
        ContentsSheet(
            isPdf = isPdf, pageCount = book.pageCount, chapters = chapters, bookmarks = bookmarks,
            onGoChapter = { idx -> goTo(idx, null); showContents = false },
            onGoPage = { p -> goTo(null, p); showContents = false },
            onGoBookmark = { bm -> goTo(bm.blockIndex, bm.pdfPage); showContents = false },
            onDeleteBookmark = vm::deleteBookmark,
            onQueue = { showContents = false; onQueue() },
            onDismiss = { showContents = false },
        )
    }
}

@Composable
private fun ThemeIcon(dark: Boolean, contentDescription: String) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp).semantics { this.contentDescription = contentDescription }) {
        val c = center
        val r = size.minDimension * 0.22f
        if (dark) {
            // Currently dark: show a sun (tap for light).
            drawCircle(tint, r, c)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                drawLine(
                    tint, c + Offset(dx * r * 1.5f, dy * r * 1.5f), c + Offset(dx * r * 2.2f, dy * r * 2.2f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        } else {
            // Currently light: show a crescent moon (tap for dark).
            val outer = Path().apply { addOval(Rect(center = c, radius = r * 2.2f)) }
            val cut = Path().apply { addOval(Rect(center = c + Offset(r * 1.2f, -r * 0.9f), radius = r * 1.9f)) }
            drawPath(Path.combine(PathOperation.Difference, outer, cut), tint)
        }
    }
}

@Composable
private fun TextReader(
    pagingItems: LazyPagingItems<BlockRow>,
    start: Int,
    translated: Boolean,
    prefs: ReaderPrefs,
    isPdf: Boolean,
    jump: ReaderJump?,
    onJumpConsumed: () -> Unit,
    onEdit: (BlockRow) -> Unit,
    onPosition: (Int, Int?, String?) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val positionCallback by rememberUpdatedState(onPosition)
    val jumpConsumed by rememberUpdatedState(onJumpConsumed)

    LaunchedEffect(listState, pagingItems) {
        snapshotFlow {
            val i = listState.firstVisibleItemIndex
            i to pagingItems.itemSnapshotList.getOrNull(i)
        }.collect { (i, row) -> positionCallback(i, row?.block?.pdfPage, row?.chapterTitle) }
    }
    LaunchedEffect(jump) {
        if (jump != null) {
            listState.scrollToItem(jump.index.coerceAtLeast(0))
            jumpConsumed()
        }
    }

    val empty = pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0
    if (empty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This book has no readable text.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = prefs.margin.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(prefs.paragraphSpacing.dp),
        ) {
            items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.block.id }) { index ->
                val row = pagingItems[index]
                if (row == null) {
                    Spacer(Modifier.size(48.dp))
                } else {
                    val prev = if (index > 0) pagingItems.peek(index - 1) else null
                    val pageLabel = isPdf && row.block.pdfPage != null &&
                        (index == 0 || (prev != null && prev.block.pdfPage != row.block.pdfPage))
                    BlockItem(row, pageLabel, translated, prefs, onEdit)
                }
            }
        }
    }
}

@Composable
private fun BlockItem(row: BlockRow, showPageLabel: Boolean, translated: Boolean, prefs: ReaderPrefs, onEdit: (BlockRow) -> Unit) {
    val b = row.block
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        if (showPageLabel) {
            Text(
                "— Page ${b.pdfPage} —", style = MaterialTheme.typography.labelSmall, color = muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        SelectionContainer {
            val noteStyle = TextStyle(
                fontFamily = fontFamilyFor(prefs.font), fontSize = (prefs.fontSize * 0.9f).sp,
                fontStyle = FontStyle.Italic, color = muted,
            )
            when {
                b.extractionStatus == BlockStatus.NO_TEXT ->
                    Text("Page ${b.pdfPage} has no selectable text. Switch to Pages view to read it.", style = noteStyle)
                translated && row.translatedText == null ->
                    Text("This section has not been translated yet.", style = noteStyle)
                else -> {
                    val text = if (translated) row.translatedText.orEmpty() else b.originalText
                    val scale = if (b.blockType == "HEADING") when (b.format) { "h1" -> 1.5f; "h2" -> 1.3f; else -> 1.15f } else 1f
                    val size = prefs.fontSize * scale
                    Text(
                        text = if (b.blockType == "LIST_ITEM") "• $text" else text,
                        style = TextStyle(
                            fontFamily = fontFamilyFor(prefs.font),
                            fontSize = size.sp,
                            lineHeight = (size * prefs.lineSpacing).sp,
                            fontWeight = if (b.blockType == "HEADING") FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (b.blockType == "QUOTE") FontStyle.Italic else FontStyle.Normal,
                            textAlign = if (prefs.justify) TextAlign.Justify else TextAlign.Start,
                            color = MaterialTheme.colorScheme.onBackground,
                        ),
                        modifier = if (b.blockType == "QUOTE") Modifier.padding(start = 16.dp) else Modifier,
                    )
                }
            }
        }
        if (translated && row.translatedText != null && b.extractionStatus == BlockStatus.OK) {
            val vs = row.verifyState ?: VerifyStates.NOT_VERIFIED
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only states the user or a pasted check actually produced are shown; "Not verified" is the honest default.
                Text(
                    VerifyStates.label(vs), style = MaterialTheme.typography.labelSmall,
                    color = if (VerifyStates.isUnresolved(vs)) MaterialTheme.colorScheme.error else muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEdit(row) }) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun PagesReader(
    vm: ReaderViewModel,
    pageCount: Int,
    start: Int,
    dark: Boolean,
    jump: ReaderJump?,
    onJumpConsumed: () -> Unit,
    onPage: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    val pageCallback by rememberUpdatedState(onPage)
    val jumpConsumed by rememberUpdatedState(onJumpConsumed)
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.collect { pageCallback(it) } }
    LaunchedEffect(jump) {
        if (jump != null) {
            state.scrollToItem(jump.index.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
            jumpConsumed()
        }
    }
    val renderer = vm.pdf
    if (renderer == null || !renderer.fileAvailable) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "The stored PDF file is unavailable, so page images can't be shown. The extracted text is still readable in Text view.",
                modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val density = LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(count = pageCount, key = { it }) { page ->
                val bmp by produceState<Bitmap?>(null, page, widthPx) { value = renderer.render(page, widthPx) }
                val img = bmp
                if (img == null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.7f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = "Page ${page + 1}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                        colorFilter = if (dark) ColorFilter.colorMatrix(InvertMatrix) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderSettingsSheet(prefs: ReaderPrefs, onChange: (ReaderPrefs.() -> ReaderPrefs) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Reader settings", style = MaterialTheme.typography.titleMedium)
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            ThemeModeSelector(prefs.themeMode) { m -> onChange { copy(themeMode = m) } }

            Text("Font", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ReaderFont.SANS to "Sans", ReaderFont.SERIF to "Serif", ReaderFont.GURMUKHI to "Noto Gurmukhi").forEach { (f, label) ->
                    FilterChip(selected = prefs.font == f, onClick = { onChange { copy(font = f) } }, label = { Text(label) })
                }
            }
            PrefSlider("Font size", prefs.fontSize, 12f..32f, { "${it.toInt()} sp" }) { v -> onChange { copy(fontSize = v) } }
            PrefSlider("Line spacing", prefs.lineSpacing, 1.0f..2.4f, { "%.1f×".format(it) }) { v -> onChange { copy(lineSpacing = v) } }
            PrefSlider("Paragraph spacing", prefs.paragraphSpacing, 0f..40f, { "${it.toInt()} dp" }) { v -> onChange { copy(paragraphSpacing = v) } }
            PrefSlider("Margins", prefs.margin, 4f..56f, { "${it.toInt()} dp" }) { v -> onChange { copy(margin = v) } }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Switch(checked = prefs.justify, onCheckedChange = { j -> onChange { copy(justify = j) } })
                Spacer(Modifier.width(12.dp))
                Text("Justify text")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = prefs.brightness < 0f, onCheckedChange = { sys -> onChange { copy(brightness = if (sys) -1f else 0.6f) } })
                Spacer(Modifier.width(12.dp))
                Text("Use system brightness")
            }
            if (prefs.brightness >= 0f) {
                PrefSlider("Reading brightness", prefs.brightness, 0.05f..1f, { "${(it * 100).toInt()}%" }) { v -> onChange { copy(brightness = v) } }
            }
        }
    }
}

@Composable
private fun PrefSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label: ${format(v)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onCommit(v) }, valueRange = range)
    }
}

@Composable
private fun ContentsSheet(
    isPdf: Boolean,
    pageCount: Int,
    chapters: List<ChapterRow>,
    bookmarks: List<BookmarkEntity>,
    onGoChapter: (Int) -> Unit,
    onGoPage: (Int) -> Unit,
    onGoBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item {
                OutlinedButton(onClick = onQueue, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("Translation queue and progress") }
            }
            if (isPdf) {
                item {
                    var text by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it.filter(Char::isDigit) }, singleLine = true,
                            label = { Text("Go to page (1–$pageCount)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { text.toIntOrNull()?.takeIf { it in 1..pageCount }?.let(onGoPage) }) { Text("Go") }
                    }
                }
            } else {
                item { Text("Chapters", style = MaterialTheme.typography.titleSmall) }
                items(chapters, key = { it.id }) { ch ->
                    Text(
                        ch.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = ch.startIndex != null) { ch.startIndex?.let(onGoChapter) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
            item { Text("Bookmarks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            if (bookmarks.isEmpty()) {
                item { Text("No bookmarks yet. Tap the star in the toolbar to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(bookmarks, key = { it.id }) { bm ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bm.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { onGoBookmark(bm) }.padding(vertical = 12.dp),
                    )
                    IconButton(onClick = { onDeleteBookmark(bm.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete bookmark") }
                }
            }
        }
    }
}
