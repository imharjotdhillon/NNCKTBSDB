package com.punjabibooktranslator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.punjabibooktranslator.AppContainer
import com.punjabibooktranslator.core.IdList
import com.punjabibooktranslator.core.JobStatus
import com.punjabibooktranslator.core.ParseResult
import com.punjabibooktranslator.core.ParsedEntry
import com.punjabibooktranslator.core.PromptBlock
import com.punjabibooktranslator.core.GlossaryMatcher
import com.punjabibooktranslator.core.PromptBuilder
import com.punjabibooktranslator.core.PromptSpec
import com.punjabibooktranslator.core.ResponseParser
import com.punjabibooktranslator.core.SizeEstimator
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.core.TranslationStyle
import com.punjabibooktranslator.data.BlockRow
import com.punjabibooktranslator.data.BlockStatus
import com.punjabibooktranslator.data.BookEntity
import com.punjabibooktranslator.data.SaveItem
import com.punjabibooktranslator.data.TranslationJobEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Stage { SETUP, PROMPT, PASTE, REVIEW, DONE }
enum class ScopeKind { PAGES_FROM_HERE, PAGES_CUSTOM, CHAPTER, FROM_HERE_TEXT, PARAGRAPH_RANGE }

data class Selection(
    /** Paragraphs that will be sent (translatable, and not yet translated if "skip translated" is on). */
    val blocks: List<BlockRow>,
    val label: String,
    val totalInRange: Int,
    val alreadyTranslated: Int,
    val chars: Int,
    val words: Int,
)

data class ReviewItem(val row: BlockRow, val text: String, val flag: String?, val edited: Boolean)

data class TState(
    val book: BookEntity? = null,
    val ready: Boolean = false,
    val stage: Stage = Stage.SETUP,
    val kind: ScopeKind = ScopeKind.PAGES_FROM_HERE,
    val pageCount: Int = 5,
    val customFrom: String = "",
    val customTo: String = "",
    val charBudget: Int = 10_000,
    val skipTranslated: Boolean = true,
    val style: TranslationStyle = TranslationStyle.LITERARY,
    val saveStyleAsDefault: Boolean = false,
    val includeContext: Boolean = true,
    val sourceLang: String = "en",
    val targetLang: String = "pa",
    val selection: Selection? = null,
    val selectionError: String? = null,
    val parts: List<List<BlockRow>> = emptyList(),
    val partIndex: Int = 0,
    val prompt: String = "",
    val promptWarning: String? = null,
    val glossaryUsed: Int = 0,
    val glossaryOmitted: Int = 0,
    val examplesUsed: Int = 0,
    val hasSummary: Boolean = false,
    val pasted: String = "",
    val parse: ParseResult? = null,
    val repairPrompt: String? = null,
    val review: List<ReviewItem> = emptyList(),
    val unknown: List<ParsedEntry> = emptyList(),
    val busy: Boolean = false,
    val doneInfo: String? = null,
    val toast: String? = null,
    /** >= 0 when this screen is working on a queued batch. */
    val jobId: Long = -1,
    val job: TranslationJobEntity? = null,
    val jobMissing: Boolean = false,
    val nextJobId: Long? = null,
)

class TranslateViewModel(
    private val c: AppContainer,
    private val bookId: Long,
    private val startBlock: Int,
    private val startPage: Int,
    private val jobId: Long = -1L,
) : ViewModel() {
    private val dao = c.db.readerDao()
    private val bookDao = c.db.bookDao()
    private val _state = MutableStateFlow(TState())
    val state: StateFlow<TState> = _state.asStateFlow()

    private var startOrder = 0
    private var currentPage = 1
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            val b = bookDao.getById(bookId) ?: return@launch
            if (jobId >= 0) { initJob(b); return@launch }
            startOrder = if (startBlock >= 0) startBlock else (dao.firstBlockIndexForPage(bookId, startPage) ?: 0)
            currentPage = if (b.format == "PDF") {
                dao.blocksByOrder(bookId, startOrder, startOrder).firstOrNull()?.block?.pdfPage ?: startPage.coerceAtLeast(1)
            } else 0
            _state.update {
                it.copy(
                    book = b, ready = true, sourceLang = b.sourceLang, targetLang = b.targetLang,
                    style = TranslationStyle.fromKey(b.translationStyle),
                    kind = if (b.format == "PDF") ScopeKind.PAGES_FROM_HERE else ScopeKind.CHAPTER,
                )
            }
            refresh()
        }
    }

    val startPageForDisplay: Int get() = currentPage

    // ---------- queued job mode ----------

    private suspend fun initJob(b: BookEntity) {
        val job = c.jobs.get(jobId)
        if (job == null || job.bookId != bookId) {
            _state.update { it.copy(book = b, ready = true, jobId = jobId, jobMissing = true) }
            return
        }
        val all = dao.blocksByIds(IdList.decode(job.blockIds))
        val promptIds = IdList.decode(job.promptBlockIds).toSet()
        // With a stored prompt, the expected ids are exactly the ones that prompt asked for; otherwise ask only for what is missing.
        val rows = if (job.prompt != null && promptIds.isNotEmpty()) all.filter { it.block.id in promptIds } else all.filter { it.translatedText == null }
        val next = c.jobs.nextOpen(bookId, job.sortKey)?.id
        val base = _state.value.copy(
            book = b, ready = true, jobId = jobId, job = job, nextJobId = next,
            sourceLang = job.sourceLang, targetLang = job.targetLang, style = TranslationStyle.fromKey(job.style),
            parts = if (rows.isEmpty()) emptyList() else listOf(rows), partIndex = 0,
            selection = if (rows.isEmpty()) null else Selection(
                rows, job.label, rows.size, 0, rows.sumOf { it.block.originalText.length },
                rows.sumOf { SizeEstimator.wordCount(it.block.originalText) },
            ),
        )
        if (rows.isEmpty()) {
            c.jobs.reconcile(bookId)
            _state.value = base.copy(stage = Stage.DONE, doneInfo = "Every paragraph in this batch already has a saved translation.")
            return
        }
        val stored = job.responseText.orEmpty()
        when (job.status) {
            JobStatus.AWAITING_RESPONSE -> {
                _state.value = base.copy(stage = Stage.PASTE, prompt = job.prompt.orEmpty(), pasted = stored)
            }
            JobStatus.REVIEW, JobStatus.FAILED -> {
                _state.value = base.copy(stage = if (job.status == JobStatus.REVIEW && stored.isBlank()) Stage.PROMPT else Stage.PASTE, prompt = job.prompt.orEmpty(), pasted = stored)
                // Rebuild the review from the stored reply. Unsaved edits made before the app closed are not kept.
                if (stored.isNotBlank()) parseResponse(persist = false)
            }
            else -> {
                if (job.prompt != null && promptIds.isNotEmpty()) _state.value = base.copy(stage = Stage.PROMPT, prompt = job.prompt)
                else { _state.value = base; generatePrompt(0) }
            }
        }
    }

    /** The user copied the prompt or opened the AI: from now on the batch is waiting for a pasted reply. */
    fun markSent() {
        if (jobId < 0) return
        viewModelScope.launch { c.jobs.markSent(jobId) }
    }

    fun goToPaste() {
        markSent()
        goTo(Stage.PASTE)
    }

    // ---------- setup ----------

    fun change(transform: (TState) -> TState) {
        _state.update(transform)
        refresh()
    }

    fun swapDirection() {
        val s = _state.value
        val b = s.book ?: return
        viewModelScope.launch { bookDao.setDirection(b.id, s.targetLang, s.sourceLang) }
        change { it.copy(sourceLang = s.targetLang, targetLang = s.sourceLang) }
    }

    fun toast(msg: String) = _state.update { it.copy(toast = msg) }
    fun clearToast() = _state.update { it.copy(toast = null) }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val s = _state.value
            val b = s.book ?: return@launch
            val r = compute(b, s)
            _state.update { it.copy(selection = r.selection, selectionError = r.error, parts = r.parts, partIndex = 0) }
        }
    }

    private class Computed(val selection: Selection?, val parts: List<List<BlockRow>>, val error: String?)

    private fun parseParaOrder(raw: String): String? {
        val t = raw.trim().uppercase()
        if (t.isEmpty()) return null
        return when {
            Regex("^P\\d+$").matches(t) -> TextUtils.paraId(t.drop(1).toInt() - 1)
            Regex("^\\d+$").matches(t) -> TextUtils.paraId(t.toInt() - 1)
            else -> null
        }
    }

    private suspend fun compute(b: BookEntity, s: TState): Computed {
        val isPdf = b.format == "PDF"
        var rangeLabel: String
        val inRange: List<BlockRow>
        when (s.kind) {
            ScopeKind.PAGES_FROM_HERE -> {
                val from = currentPage.coerceAtLeast(1)
                val to = minOf(b.pageCount, from + s.pageCount - 1)
                inRange = dao.blocksByPages(bookId, from, to)
                rangeLabel = "PDF pages $from–$to"
            }
            ScopeKind.PAGES_CUSTOM -> {
                val from = s.customFrom.trim().toIntOrNull()
                val to = s.customTo.trim().toIntOrNull()
                if (from == null || to == null) return Computed(null, emptyList(), "Enter the first and last page.")
                if (from < 1 || to > b.pageCount || from > to) return Computed(null, emptyList(), "Pages must be between 1 and ${b.pageCount}, first ≤ last.")
                inRange = dao.blocksByPages(bookId, from, to)
                rangeLabel = "PDF pages $from–$to"
            }
            ScopeKind.CHAPTER -> {
                val chapterId = dao.chapterIdAt(bookId, startOrder) ?: return Computed(null, emptyList(), "This position isn't inside a chapter.")
                inRange = dao.blocksByChapter(bookId, chapterId)
                rangeLabel = inRange.firstOrNull()?.chapterTitle ?: "Current chapter"
            }
            ScopeKind.FROM_HERE_TEXT -> {
                val window = dao.blocksByOrder(bookId, startOrder, startOrder + 600)
                val picked = ArrayList<BlockRow>()
                var chars = 0
                for (r in window) {
                    val len = r.block.originalText.length
                    if (r.block.extractionStatus == BlockStatus.OK) {
                        if (picked.any { it.block.extractionStatus == BlockStatus.OK } && chars + len > s.charBudget) break
                        chars += len
                    }
                    picked.add(r)
                }
                inRange = picked
                val titles = picked.mapNotNull { it.chapterTitle }.distinct()
                rangeLabel = "From the current position" + if (titles.isNotEmpty()) " (${titles.first()}${if (titles.size > 1) " → ${titles.last()}" else ""})" else ""
            }
            ScopeKind.PARAGRAPH_RANGE -> {
                val fromId = parseParaOrder(s.customFrom)
                val toId = parseParaOrder(s.customTo)
                if (fromId == null || toId == null) return Computed(null, emptyList(), "Enter paragraph ids like P0012 (or just 12).")
                val fo = dao.orderOfParaId(bookId, fromId)
                val to = dao.orderOfParaId(bookId, toId)
                if (fo == null || to == null) return Computed(null, emptyList(), "Paragraph id not found in this book.")
                if (fo > to) return Computed(null, emptyList(), "First paragraph must come before the last.")
                inRange = dao.blocksByOrder(bookId, fo, to)
                rangeLabel = "Paragraphs $fromId–$toId"
            }
        }
        val translatable = inRange.filter { it.block.extractionStatus == BlockStatus.OK }
        val already = translatable.count { it.translatedText != null }
        val chosen = if (s.skipTranslated) translatable.filter { it.translatedText == null } else translatable
        if (chosen.isEmpty()) {
            val msg = if (translatable.isEmpty()) "There is no readable text in this range (scanned pages need OCR, a later phase)."
            else "Everything in this range already has a translation. Turn off “Skip already translated” to redo it."
            return Computed(null, emptyList(), msg)
        }
        val byId = chosen.associateBy { it.block.paraId }
        val parts = SizeEstimator.split(chosen.map { PromptBlock(it.block.paraId, it.block.originalText) }, s.targetLang)
            .map { part -> part.map { byId.getValue(it.id) } }
        val chars = chosen.sumOf { it.block.originalText.length }
        val words = chosen.sumOf { SizeEstimator.wordCount(it.block.originalText) }
        return Computed(Selection(chosen, rangeLabel, translatable.size, already, chars, words), parts, null)
    }

    // ---------- prompt ----------

    private fun cap(t: String) = if (t.length > 700) t.take(700) + "…" else t

    fun generatePrompt(partIndex: Int = 0) {
        val s = _state.value
        val b = s.book ?: return
        val part = s.parts.getOrNull(partIndex) ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val first = part.first().block
            val last = part.last().block
            val before = if (s.includeContext) dao.blocksByOrder(bookId, maxOf(0, first.textOrder - 2), first.textOrder - 1) else emptyList()
            val after = if (s.includeContext) dao.blocksByOrder(bookId, last.textOrder + 1, last.textOrder + 1) else emptyList()
            val summary = first.chapterId?.let { dao.chapterSummary(it) }?.takeIf { it.isNotBlank() }
            // Only approved glossary entries that actually occur in this batch are sent; drafts never are.
            val rel = c.glossary.relevantFor(b.id, part.map { it.block.originalText })
            val examples = c.context.approvedExamples(b.id, rel.terms.map { it.term })
            val pages = part.mapNotNull { it.block.pdfPage }
            val location = if (pages.isNotEmpty()) "PDF page${if (pages.min() != pages.max()) "s" else ""} ${pages.min()}–${pages.max()}"
            else "paragraphs ${first.paraId}–${last.paraId}"
            val spec = PromptSpec(
                bookTitle = b.title, author = b.author, chapterTitle = part.first().chapterTitle, locationLabel = location,
                sourceLang = s.sourceLang, targetLang = s.targetLang, style = s.style,
                blocks = part.map { PromptBlock(it.block.paraId, it.block.originalText) },
                contextBefore = before.filter { it.block.extractionStatus == BlockStatus.OK }.map { PromptBlock(it.block.paraId, cap(it.block.originalText)) },
                contextAfter = after.filter { it.block.extractionStatus == BlockStatus.OK }.map { PromptBlock(it.block.paraId, cap(it.block.originalText)) },
                chapterSummary = summary,
                glossary = GlossaryMatcher.promptLines(rel.terms),
                approvedExamples = examples,
            )
            val prompt = PromptBuilder.build(spec)
            val promptTokens = SizeEstimator.tokens(prompt.length, s.sourceLang)
            val warn = if (promptTokens > SizeEstimator.MAX_PROMPT_TOKENS)
                "This prompt is very long (about $promptTokens tokens). the AI may reject it; choose a smaller selection." else null
            if (s.saveStyleAsDefault) bookDao.setStyle(b.id, s.style.name)
            if (jobId >= 0) c.jobs.savePrompt(jobId, prompt, part.map { it.block.id })
            _state.update {
                it.copy(stage = Stage.PROMPT, partIndex = partIndex, prompt = prompt, promptWarning = warn, busy = false,
                    glossaryUsed = rel.terms.size, glossaryOmitted = rel.omitted, examplesUsed = examples.size, hasSummary = summary != null,
                    pasted = if (partIndex == it.partIndex && it.stage != Stage.SETUP) it.pasted else "", parse = null, repairPrompt = null)
            }
        }
    }

    // ---------- paste / parse / review ----------

    fun setPasted(text: String) = _state.update { it.copy(pasted = text, parse = null, repairPrompt = null) }
    fun goTo(stage: Stage) = _state.update { it.copy(stage = stage) }

    fun back(): Boolean {
        val s = _state.value
        val prev = when (s.stage) {
            Stage.SETUP -> return false
            Stage.PROMPT -> if (jobId >= 0) return false else Stage.SETUP
            Stage.PASTE -> Stage.PROMPT
            Stage.REVIEW -> Stage.PASTE
            Stage.DONE -> if (jobId >= 0) return false else Stage.SETUP
        }
        _state.update { it.copy(stage = prev) }
        return true
    }

    fun parseResponse(persist: Boolean = true) {
        val s = _state.value
        val part = s.parts.getOrNull(s.partIndex) ?: return
        val ids = part.map { it.block.paraId }
        val result = ResponseParser.parse(s.pasted, ids)
        if (result.error != null) {
            if (jobId >= 0 && persist) viewModelScope.launch { c.jobs.markFailed(jobId, s.pasted, result.error) }
            _state.update {
                it.copy(parse = result, repairPrompt = if (s.pasted.isNotBlank()) PromptBuilder.buildRepair(ids, s.pasted, result.error) else null)
            }
            return
        }
        val items = part.map { row ->
            val id = row.block.paraId
            val flag = when {
                id in result.duplicateIds -> "Appeared twice with different text. Check which is right."
                id in result.emptyIds -> "The AI returned an empty translation."
                id in result.missingIds -> if (result.truncated) "Missing: the response was cut off." else "Missing from the AI's response."
                result.recovered -> "Recovered from a damaged response. Please check."
                else -> null
            }
            ReviewItem(row, result.proposals[id].orEmpty(), flag, edited = false)
        }
        val problem = buildList {
            if (result.truncated) add("it was cut off before the end")
            if (result.missingIds.isNotEmpty()) add("some paragraphs were missing")
            if (result.recovered && !result.truncated) add("it was not valid JSON")
        }.joinToString(" and ")
        if (jobId >= 0 && persist) viewModelScope.launch { c.jobs.markReview(jobId, s.pasted) }
        _state.update {
            it.copy(
                parse = result, stage = Stage.REVIEW, review = items, unknown = result.unknownEntries,
                repairPrompt = if (problem.isNotEmpty()) PromptBuilder.buildRepair(ids, s.pasted, problem) else null,
            )
        }
    }

    fun editReview(blockId: Long, text: String) = _state.update { st ->
        st.copy(review = st.review.map { if (it.row.block.id == blockId) it.copy(text = text, edited = true) else it })
    }

    fun saveReview() {
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val items = s.review.filter { it.text.isNotBlank() }
                .map { SaveItem(it.row.block.id, it.text, flagged = it.flag != null, edited = it.edited) }
            val res = c.translations.save(items, s.sourceLang, s.targetLang, s.style, jobId.takeIf { it >= 0 })
            val blank = s.review.count { it.text.isBlank() }
            val info = buildString {
                append("Saved ${res.saved} translation${if (res.saved == 1) "" else "s"}")
                if (res.unchanged > 0) append(" (${res.unchanged} identical to what was already saved)")
                append(". ")
                if (blank > 0) append("$blank paragraph${if (blank == 1) " has" else "s have"} no translation yet and will stay untranslated.")
                else append("Every paragraph in this part is now translated.")
            }
            val jobNote = when (res.jobStatus) {
                null -> ""
                JobStatus.COMPLETED -> " This batch is complete."
                else -> " This batch is marked incomplete: ${res.jobMissing} paragraph${if (res.jobMissing == 1) " is" else "s are"} still untranslated. Use Retry in the queue to ask the AI for just those."
            }
            val next = if (jobId >= 0) s.job?.let { c.jobs.nextOpen(bookId, it.sortKey)?.id } else null
            _state.update { it.copy(busy = false, stage = Stage.DONE, doneInfo = info + jobNote, nextJobId = next) }
            if (jobId < 0) refresh() // ad-hoc: remaining parts are recomputed from what is actually saved
        }
    }
}
