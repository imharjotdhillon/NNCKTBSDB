package com.punjabibooktranslator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.cachedIn
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.data.BlockRow
import com.punjabibooktranslator.data.BookmarkEntity
import com.punjabibooktranslator.data.ReaderPrefs
import com.punjabibooktranslator.data.ReadingProgressEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Where the reader was, as stored in the database. [pdfPage] is 1-based. */
data class ReaderInitial(val blockIndex: Int, val pdfPage: Int, val displayMode: String, val viewMode: String)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ReaderViewModel(private val c: AppContainer, val bookId: Long) : ViewModel() {
    private val dao = c.db.readerDao()
    private val whileSubscribed = SharingStarted.WhileSubscribed(5_000)

    val book = c.db.bookDao().observeBook(bookId).stateIn(viewModelScope, whileSubscribed, null)
    val prefs: StateFlow<ReaderPrefs> = c.prefs.prefs.stateIn(viewModelScope, whileSubscribed, ReaderPrefs())
    val blockCount: StateFlow<Int> = dao.observeBlockCount(bookId).stateIn(viewModelScope, whileSubscribed, 0)
    val chapters = dao.observeChapters(bookId).stateIn(viewModelScope, whileSubscribed, emptyList())
    val bookmarks = dao.observeBookmarks(bookId).stateIn(viewModelScope, whileSubscribed, emptyList())

    private val _initial = MutableStateFlow<ReaderInitial?>(null)
    val initial: StateFlow<ReaderInitial?> = _initial.asStateFlow()
    private val saved = MutableStateFlow<ReaderInitial?>(null)

    var pdf: PdfPageRenderer? = null
        private set

    suspend fun saveEdit(blockId: Long, text: String): Boolean = c.review.edit(blockId, text)
    suspend fun revisions(blockId: Long) = c.review.revisions(blockId)
    suspend fun revertTo(blockId: Long, revisionId: Long): Boolean = c.review.revert(blockId, revisionId)

    /** Paged, lazily loaded text blocks; starts around the saved reading position. */
    val pages: Flow<PagingData<BlockRow>> = _initial.filterNotNull().take(1).flatMapLatest { init ->
        Pager(
            config = PagingConfig(pageSize = 40, initialLoadSize = 80, enablePlaceholders = true),
            initialKey = init.blockIndex.coerceAtLeast(0),
        ) { dao.pagingSource(bookId) }.flow
    }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            val b = c.db.bookDao().getById(bookId)
            val p = dao.getProgress(bookId)
            val mostlyScanned = b != null && b.format == "PDF" && b.pageCount > 0 && b.noTextPages * 2 > b.pageCount
            val init = ReaderInitial(
                blockIndex = p?.lastBlockIndex ?: 0,
                pdfPage = p?.lastPdfPage ?: 1,
                displayMode = p?.displayMode ?: "ORIGINAL",
                viewMode = if (b?.format != "PDF") "TEXT" else p?.viewMode ?: if (mostlyScanned) "PAGES" else "TEXT",
            )
            if (b != null && b.format == "PDF") pdf = PdfPageRenderer(File(b.localPath))
            saved.value = init
            _initial.value = init
        }
        viewModelScope.launch { saved.filterNotNull().debounce(600).collect { persist(it) } }
    }

    private suspend fun persist(s: ReaderInitial) {
        var index = s.blockIndex
        if (s.viewMode == "PAGES") dao.firstBlockIndexForPage(bookId, s.pdfPage)?.let { index = it }
        dao.upsertProgress(
            ReadingProgressEntity(bookId, index, s.pdfPage, s.displayMode, s.viewMode, System.currentTimeMillis())
        )
    }

    fun reportText(index: Int, pdfPage: Int?) = saved.update { it?.copy(blockIndex = index, pdfPage = pdfPage ?: it.pdfPage) }
    fun reportPage(page: Int) = saved.update { it?.copy(pdfPage = page) }
    fun setDisplayMode(mode: String) = saved.update { it?.copy(displayMode = mode) }
    fun setViewMode(mode: String) = saved.update { it?.copy(viewMode = mode) }

    suspend fun blockIndexForPage(page: Int): Int? = dao.firstBlockIndexForPage(bookId, page)

    fun updatePrefs(transform: ReaderPrefs.() -> ReaderPrefs) {
        viewModelScope.launch { c.prefs.update(transform) }
    }

    fun addBookmark(label: String, textIndex: Int, pdfPage: Int?, inPagesView: Boolean) {
        viewModelScope.launch {
            val index = if (inPagesView && pdfPage != null) dao.firstBlockIndexForPage(bookId, pdfPage) ?: textIndex else textIndex
            dao.insertBookmark(
                BookmarkEntity(bookId = bookId, blockIndex = index, pdfPage = pdfPage, label = label, createdAt = System.currentTimeMillis())
            )
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { dao.deleteBookmark(id) }
    }

    override fun onCleared() {
        val last = saved.value
        val renderer = pdf
        c.appScope.launch {
            last?.let { persist(it) }
            renderer?.close()
        }
    }
}
