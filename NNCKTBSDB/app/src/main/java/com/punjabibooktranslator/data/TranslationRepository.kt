package com.punjabibooktranslator.data

import androidx.room.withTransaction
import com.punjabibooktranslator.core.IdList
import com.punjabibooktranslator.core.JobRules
import com.punjabibooktranslator.core.JobStatus
import com.punjabibooktranslator.core.SaveEngine
import com.punjabibooktranslator.core.SaveInput
import com.punjabibooktranslator.core.StoredTranslation
import com.punjabibooktranslator.core.TranslationStyle

data class SaveItem(
    val blockId: Long,
    val text: String,
    /** The paragraph was flagged during import (missing/duplicate/recovered). */
    val flagged: Boolean,
    /** The user edited the text in the review screen. */
    val edited: Boolean,
)

data class SaveResult(
    val saved: Int,
    val unchanged: Int,
    /** Set only when the save belonged to a queue job. */
    val jobStatus: String? = null,
    val jobMissing: Int = 0,
)

class TranslationRepository(private val db: AppDatabase) {

    /**
     * Saves reviewed translations in ONE transaction (decision logic: [SaveEngine], unit-tested). If [jobId] is given,
     * the job's status is settled in the same transaction from what is REALLY saved afterwards, so a crash can never
     * leave a job claiming completion without its translations, or the reverse.
     */
    suspend fun save(
        items: List<SaveItem>,
        sourceLang: String,
        targetLang: String,
        style: TranslationStyle,
        jobId: Long? = null,
    ): SaveResult = db.withTransaction {
        val dao = db.readerDao()
        val now = System.currentTimeMillis()
        val existing = items.map { it.blockId }.chunked(500).flatMap { dao.translationsFor(it) }
            .associate { it.textBlockId to StoredTranslation(it.id, it.translatedText, it.status, it.targetLang, it.createdAt, it.revision, it.userApproved, it.verifyState) }
        val outcome = SaveEngine.plan(existing, items.map { SaveInput(it.blockId, it.text, it.flagged, it.edited) }, targetLang, now)
        val oldRevisions = outcome.writes.filter { it.existingId != 0L }.map { it.blockId }.chunked(500)
            .flatMap { dao.translationsFor(it) }.associateBy { it.textBlockId }
        for (w in outcome.writes) {
            val entity = TranslationEntity(
                id = w.existingId, textBlockId = w.blockId, translatedText = w.text, sourceLang = sourceLang,
                targetLang = targetLang, style = style.name, status = w.status, createdAt = w.createdAt,
                updatedAt = now, revision = w.revision, userApproved = w.userApproved, verifyState = w.verifyState,
            )
            if (w.existingId == 0L) {
                dao.insertTranslation(entity)
            } else {
                // The text being replaced is kept before the row changes; open findings about it no longer apply.
                if (w.previousText != null) {
                    db.revisionDao().insert(
                        RevisionEntity(
                            textBlockId = w.blockId, translatedText = w.previousText,
                            revision = oldRevisions[w.blockId]?.revision ?: (w.revision - 1), reason = "REPLACED_BY_IMPORT", createdAt = now,
                        )
                    )
                    db.findingDao().supersedeOpen(w.blockId, now)
                }
                dao.updateTranslation(entity)
            }
        }
        var jobStatus: String? = null
        var missing = 0
        if (jobId != null) {
            val jobDao = db.jobDao()
            val job = jobDao.get(jobId)
            if (job != null) {
                val ids = IdList.decode(job.blockIds)
                val translatedNow = ids.chunked(500).sumOf { jobDao.countTranslated(it) }
                jobStatus = JobRules.afterSave(ids.size, translatedNow)
                missing = ids.size - translatedNow
                jobDao.update(
                    job.copy(
                        status = jobStatus, updatedAt = now,
                        lastError = if (jobStatus == JobStatus.COMPLETED) null else job.lastError,
                        responseText = if (jobStatus == JobStatus.COMPLETED) null else job.responseText,
                    )
                )
            }
        }
        SaveResult(outcome.writes.size, outcome.unchanged, jobStatus, missing)
    }
}
