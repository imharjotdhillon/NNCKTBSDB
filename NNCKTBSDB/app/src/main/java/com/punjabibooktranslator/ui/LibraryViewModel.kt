package com.punjabibooktranslator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.data.LibraryRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class SortOrder(val label: String) {
    RECENT("Recently opened"),
    TITLE("Title"),
    TRANSLATION("Translation progress"),
}

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(SortOrder.RECENT)
    val grid = MutableStateFlow(true)
    val messages = c.importer.messages

    val rows: StateFlow<List<LibraryRow>?> =
        combine(c.db.bookDao().observeLibrary(), query, sort) { all, q, s ->
            val filtered = if (q.isBlank()) all else all.filter {
                it.book.title.contains(q, ignoreCase = true) || (it.book.author?.contains(q, ignoreCase = true) == true)
            }
            when (s) {
                SortOrder.RECENT -> filtered.sortedByDescending { it.lastOpenedAt ?: it.book.importedAt }
                SortOrder.TITLE -> filtered.sortedBy { it.book.title.lowercase() }
                SortOrder.TRANSLATION -> filtered.sortedByDescending {
                    if (it.totalBlocks == 0) 0.0 else it.translatedBlocks.toDouble() / it.totalBlocks
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(q: String) { query.value = q }
    fun setSort(s: SortOrder) { sort.value = s }
    fun toggleGrid() { grid.update { !it } }
    fun import(uri: android.net.Uri) = c.importer.enqueueImport(uri)
    fun retry(bookId: Long) = c.importer.enqueueRetry(bookId)
    fun delete(bookId: Long) = c.importer.enqueueDelete(bookId)
}
