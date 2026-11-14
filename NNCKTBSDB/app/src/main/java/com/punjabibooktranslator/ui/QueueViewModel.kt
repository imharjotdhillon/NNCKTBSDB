package com.punjabibooktranslator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.BookProgress
import com.punjabibooktranslator.core.ProgressCalc
import com.punjabibooktranslator.core.TranslationStyle
import com.punjabibooktranslator.core.UnitAgg
import com.punjabibooktranslator.data.BookEntity
import com.punjabibooktranslator.data.JobView
import com.punjabibooktranslator.data.PlanRequest
import com.punjabibooktranslator.data.ReadingProgressEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlannerState(
    /** 1, 5, 10, 20 or -1 for "custom". */
    val pagesPerBatch: Int = 5,
    val customSize: String = "",
    val fromPage: String = "",
    val toPage: String = "",
    val charBudget: Int = 10_000,
    val customChars: String = "",
    val skipTranslated: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModel(private val c: AppContainer, val bookId: Long) : ViewModel() {
    private val whileSubscribed = SharingStarted.WhileSubscribed(5_000)
    private val readerDao = c.db.readerDao()

    val book: StateFlow<BookEntity?> = c.db.bookDao().observeBook(bookId).stateIn(viewModelScope, whileSubscribed, null)

    private val units: StateFlow<List<UnitAgg>?> = c.db.bookDao().observeBook(bookId).filterNotNull().flatMapLatest {
        if (it.format == "PDF") readerDao.observePageUnits(bookId) else readerDao.observeChapterUnits(bookId)
    }.stateIn(viewModelScope, whileSubscribed, null)

    val progress: StateFlow<BookProgress?> = units.map { u -> u?.let(ProgressCalc::compute) }.stateIn(viewModelScope, whileSubscribed, null)

    /** Jobs with live "how many paragraphs are really saved" counts. Recomputed whenever translations change. */
    val jobs: StateFlow<List<JobView>?> = combine(c.db.jobDao().observeJobs(bookId), units) { j, _ -> j }
        .map { list -> list.map { c.jobs.view(it) } }
        .stateIn(viewModelScope, whileSubscribed, null)

    private val _planner = MutableStateFlow(PlannerState())
    val planner: StateFlow<PlannerState> = _planner.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Statuses must follow what is really saved (e.g. paragraphs translated through the ad-hoc screen).
        viewModelScope.launch { units.filterNotNull().collect { c.jobs.reconcile(bookId) } }
    }

    fun plan(transform: (PlannerState) -> PlannerState) = _planner.update(transform)

    fun addToQueue() {
        val b = book.value ?: return
        val p = _planner.value
        val request: PlanRequest = if (b.format == "PDF") {
            val size = if (p.pagesPerBatch > 0) p.pagesPerBatch else p.customSize.trim().toIntOrNull()
            if (size == null || size < 1 || size > 200) { say("Enter a batch size between 1 and 200 pages."); return }
            val from = p.fromPage.trim().ifEmpty { "1" }.toIntOrNull()
            val to = p.toPage.trim().ifEmpty { b.pageCount.toString() }.toIntOrNull()
            if (from == null || to == null || from < 1 || to > b.pageCount || from > to) { say("Pages must be between 1 and ${b.pageCount}, first ≤ last."); return }
            PlanRequest.Pdf(size, from, to, p.skipTranslated)
        } else {
            val chars = if (p.charBudget > 0) p.charBudget else p.customChars.trim().toIntOrNull()
            if (chars == null || chars < 1000 || chars > 100_000) { say("Enter a size between 1,000 and 100,000 characters."); return }
            PlanRequest.Text(chars, p.skipTranslated)
        }
        viewModelScope.launch {
            val r = c.jobs.enqueue(b, TranslationStyle.fromKey(b.translationStyle), request)
            say(
                when {
                    r.batches == 0 && r.alreadyQueued > 0 -> "Nothing new to add: those ${r.alreadyQueued} paragraphs are already in queued batches."
                    r.batches == 0 -> "Nothing to add: everything in that range is already translated, or has no readable text."
                    else -> "Added ${r.batches} batch${if (r.batches == 1) "" else "es"} (${r.paragraphs} paragraphs)." +
                        if (r.alreadyQueued > 0) " ${r.alreadyQueued} paragraphs were skipped because they are already queued." else ""
                }
            )
        }
    }

    private fun say(m: String) { _messages.tryEmit(m) }

    fun pause(id: Long) = viewModelScope.launch { c.jobs.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { c.jobs.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { c.jobs.cancel(id) }
    fun remove(id: Long) = viewModelScope.launch { c.jobs.remove(id) }
    fun retry(id: Long, then: () -> Unit) { viewModelScope.launch { c.jobs.retry(id); then() } }
    fun pauseAll() = viewModelScope.launch { c.jobs.pauseAll(bookId) }
    fun resumeAll() = viewModelScope.launch { c.jobs.resumeAll(bookId) }
    fun clearPlanned() = viewModelScope.launch {
        val n = c.jobs.clearPlanned(bookId)
        say("Removed $n planned batch${if (n == 1) "" else "es"}. Saved translations were not touched.")
    }

    /** Points the reader at [order] so "go to section" opens exactly there. */
    fun prepareReader(order: Int, page: Int?, translated: Boolean, then: () -> Unit) {
        viewModelScope.launch {
            val old = readerDao.getProgress(bookId)
            readerDao.upsertProgress(
                ReadingProgressEntity(
                    bookId = bookId, lastBlockIndex = order, lastPdfPage = page ?: old?.lastPdfPage ?: 1,
                    displayMode = if (translated) "TRANSLATED" else "ORIGINAL", viewMode = "TEXT",
                    lastOpenedAt = System.currentTimeMillis(),
                )
            )
            then()
        }
    }
}
