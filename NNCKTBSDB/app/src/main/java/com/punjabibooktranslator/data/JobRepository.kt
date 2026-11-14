package com.punjabibooktranslator.data

import androidx.room.withTransaction
import com.punjabibooktranslator.core.BatchPlanner
import com.punjabibooktranslator.core.IdList
import com.punjabibooktranslator.core.JobRules
import com.punjabibooktranslator.core.JobStatus
import com.punjabibooktranslator.core.PlanBlock
import com.punjabibooktranslator.core.PlannedBatch
import com.punjabibooktranslator.core.TranslationStyle

sealed class PlanRequest {
    data class Pdf(val pagesPerBatch: Int, val startPage: Int, val endPage: Int, val skipTranslated: Boolean) : PlanRequest()
    data class Text(val charBudget: Int, val skipTranslated: Boolean) : PlanRequest()
}

data class EnqueueResult(
    val batches: Int,
    val paragraphs: Int,
    /** Paragraphs that would have been planned but are already part of an open batch. */
    val alreadyQueued: Int,
)

/** A job plus how many of its paragraphs are really saved right now. */
data class JobView(val job: TranslationJobEntity, val translated: Int) {
    val missing: Int get() = (job.paraCount - translated).coerceAtLeast(0)
    val status: String get() = JobRules.reconcile(job.status, job.paraCount, translated)
}

class JobRepository(private val db: AppDatabase) {
    private val dao get() = db.jobDao()

    suspend fun get(id: Long) = dao.get(id)

    suspend fun translatedCount(job: TranslationJobEntity): Int =
        IdList.decode(job.blockIds).chunked(500).sumOf { dao.countTranslated(it) }

    suspend fun view(job: TranslationJobEntity) = JobView(job, translatedCount(job))

    /** Plans batches and stores them as PENDING jobs. Never changes any translation. */
    suspend fun enqueue(book: BookEntity, style: TranslationStyle, request: PlanRequest): EnqueueResult = db.withTransaction {
        val rows = db.readerDao().planRows(book.id).map {
            PlanBlock(it.blockId, it.paraId, it.ord, it.page, it.chapterId, it.chapterTitle, it.chars, it.translated)
        }
        val open = dao.listJobs(book.id).filter { JobRules.isOpen(it.status) }
        val covered = open.flatMapTo(HashSet()) { IdList.decode(it.blockIds) }

        fun plan(blocks: List<PlanBlock>): List<PlannedBatch> = when (request) {
            is PlanRequest.Pdf -> BatchPlanner.planPdf(blocks, request.pagesPerBatch, request.startPage, request.endPage, book.targetLang, request.skipTranslated)
            is PlanRequest.Text -> BatchPlanner.planText(blocks, request.charBudget, book.targetLang, request.skipTranslated)
        }
        val everything = plan(rows)
        val fresh = plan(rows.filter { it.blockId !in covered })
        val alreadyQueued = everything.sumOf { it.blockIds.size } - fresh.sumOf { it.blockIds.size }

        val now = System.currentTimeMillis()
        var key = dao.maxSortKey(book.id)
        val size = when (request) { is PlanRequest.Pdf -> request.pagesPerBatch; is PlanRequest.Text -> request.charBudget }
        val jobs = fresh.map { b ->
            TranslationJobEntity(
                bookId = book.id, label = b.label + if (b.partCount > 1) " · part ${b.partIndex + 1}/${b.partCount}" else "",
                mode = if (request is PlanRequest.Pdf) "PDF_PAGES" else "TEXT",
                blockIds = IdList.encode(b.blockIds), paraCount = b.blockIds.size, chars = b.chars,
                firstOrder = b.firstOrder, lastOrder = b.lastOrder, firstPage = b.firstPage, lastPage = b.lastPage,
                partIndex = b.partIndex, partCount = b.partCount, batchSize = size,
                sourceLang = book.sourceLang, targetLang = book.targetLang, style = style.name,
                status = JobStatus.PENDING, prompt = null, promptBlockIds = null, responseText = null, lastError = null,
                sortKey = ++key, createdAt = now, updatedAt = now,
            )
        }
        if (jobs.isNotEmpty()) dao.insertAll(jobs)
        EnqueueResult(jobs.size, jobs.sumOf { it.paraCount }, alreadyQueued)
    }

    private suspend fun change(id: Long, f: (TranslationJobEntity) -> TranslationJobEntity) = db.withTransaction {
        dao.get(id)?.let { dao.update(f(it).copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun pause(id: Long) = change(id) { if (JobRules.canPause(it.status)) it.copy(status = JobStatus.PAUSED) else it }
    suspend fun resume(id: Long) = change(id) { if (JobRules.canResume(it.status)) it.copy(status = JobStatus.PENDING) else it }
    suspend fun cancel(id: Long) = change(id) { if (JobRules.canCancel(it.status)) it.copy(status = JobStatus.CANCELLED) else it }

    /** Retry: forgets the old prompt and reply (so the next prompt only asks for what is still missing). */
    suspend fun retry(id: Long) = change(id) {
        if (JobRules.canRetry(it.status)) it.copy(status = JobStatus.PENDING, prompt = null, promptBlockIds = null, responseText = null, lastError = null) else it
    }

    suspend fun pauseAll(bookId: Long) = dao.moveAll(bookId, JobStatus.PENDING, JobStatus.PAUSED, System.currentTimeMillis())
    suspend fun resumeAll(bookId: Long) = dao.moveAll(bookId, JobStatus.PAUSED, JobStatus.PENDING, System.currentTimeMillis())

    /** Removes the job record only. Saved translations are never touched. */
    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun clearPlanned(bookId: Long) = dao.deletePlanned(bookId)

    suspend fun savePrompt(id: Long, prompt: String, promptIds: List<Long>) = change(id) {
        it.copy(prompt = prompt, promptBlockIds = IdList.encode(promptIds), status = JobRules.afterGenerate(it.status))
    }

    suspend fun markSent(id: Long) = change(id) { it.copy(status = JobRules.afterMarkSent(it.status)) }

    suspend fun markReview(id: Long, response: String) = change(id) {
        it.copy(status = JobStatus.REVIEW, responseText = response, lastError = null)
    }

    suspend fun markFailed(id: Long, response: String, error: String) = change(id) {
        it.copy(status = JobStatus.FAILED, responseText = response, lastError = error)
    }

    suspend fun nextOpen(bookId: Long, afterSortKey: Int) = dao.nextOpen(bookId, afterSortKey)

    /** Corrects stored statuses that disagree with what is really saved (e.g. translated via the ad-hoc screen). */
    suspend fun reconcile(bookId: Long) = db.withTransaction {
        for (job in dao.listJobs(bookId)) {
            if (job.status == JobStatus.CANCELLED || job.status == JobStatus.PAUSED) continue
            val translated = translatedCount(job)
            val ids = job.paraCount
            val next = when {
                translated >= ids && job.status != JobStatus.COMPLETED -> JobStatus.COMPLETED
                else -> JobRules.reconcile(job.status, ids, translated)
            }
            if (next != job.status) {
                dao.update(job.copy(status = next, updatedAt = System.currentTimeMillis(),
                    responseText = if (next == JobStatus.COMPLETED) null else job.responseText))
            }
        }
    }
}
