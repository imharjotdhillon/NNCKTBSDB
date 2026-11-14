package com.punjabibooktranslator.core

/** Persisted status of a batch job. Nothing here ever means "The AI is working": the user submits manually. */
object JobStatus {
    /** Planned by the app; no prompt prepared yet. */
    const val PENDING = "PENDING"
    /** User paused queue preparation; hidden from "next up" until resumed. */
    const val PAUSED = "PAUSED"
    /** A prompt has been generated but the user has not said they sent it. */
    const val PREPARED = "PREPARED"
    /** The user copied the prompt / opened the AI; waiting for the reply they paste back. */
    const val AWAITING_RESPONSE = "AWAITING_RESPONSE"
    /** A reply was pasted and read; waiting for the user to review and save. */
    const val REVIEW = "REVIEW"
    /** Every paragraph of the job has a saved translation. */
    const val COMPLETED = "COMPLETED"
    /** Saved, but some paragraphs still have no translation. */
    const val INCOMPLETE = "INCOMPLETE"
    /** The pasted reply could not be read at all. */
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

enum class QueueGroup(val title: String, val hint: String) {
    AWAITING("Waiting for your AI reply", "You marked these as sent. Paste the AI's reply to continue."),
    REVIEW("Imported, awaiting your review", "A reply was read. Review and save it."),
    PROBLEM("Failed or incomplete", "The reply was unreadable, or some paragraphs are still missing."),
    PREPARED("Prompts ready, not sent yet", "A prompt exists but you haven't sent it to the AI."),
    PENDING("Pending batches", "Planned only. Nothing has been prepared or sent."),
    PAUSED("Paused", "Skipped until you resume them."),
    COMPLETED("Completed", "Every paragraph in these batches has a saved translation."),
    CANCELLED("Cancelled", "Translations already saved from these batches are kept."),
}

object JobRules {
    fun group(status: String): QueueGroup = when (status) {
        JobStatus.AWAITING_RESPONSE -> QueueGroup.AWAITING
        JobStatus.REVIEW -> QueueGroup.REVIEW
        JobStatus.FAILED, JobStatus.INCOMPLETE -> QueueGroup.PROBLEM
        JobStatus.PREPARED -> QueueGroup.PREPARED
        JobStatus.PAUSED -> QueueGroup.PAUSED
        JobStatus.COMPLETED -> QueueGroup.COMPLETED
        JobStatus.CANCELLED -> QueueGroup.CANCELLED
        else -> QueueGroup.PENDING
    }

    /** Display order of groups: things needing the user's attention first. */
    val groupOrder: List<QueueGroup> = listOf(
        QueueGroup.REVIEW, QueueGroup.AWAITING, QueueGroup.PROBLEM, QueueGroup.PREPARED,
        QueueGroup.PENDING, QueueGroup.PAUSED, QueueGroup.COMPLETED, QueueGroup.CANCELLED,
    )

    /** Pausing only applies to work that has not been sent anywhere yet. */
    fun canPause(status: String) = status == JobStatus.PENDING || status == JobStatus.PREPARED
    fun canResume(status: String) = status == JobStatus.PAUSED
    fun canCancel(status: String) = status in setOf(
        JobStatus.PENDING, JobStatus.PAUSED, JobStatus.PREPARED, JobStatus.AWAITING_RESPONSE,
        JobStatus.REVIEW, JobStatus.FAILED, JobStatus.INCOMPLETE,
    )
    fun canRetry(status: String) = status in setOf(JobStatus.FAILED, JobStatus.INCOMPLETE, JobStatus.CANCELLED)
    fun isOpen(status: String) = status != JobStatus.COMPLETED && status != JobStatus.CANCELLED

    /** Removing never deletes saved translations, but these states hold a pasted reply, so the UI confirms first. */
    fun needsConfirmToRemove(status: String) = status == JobStatus.AWAITING_RESPONSE || status == JobStatus.REVIEW

    /** Status after a prompt is (re)generated. Later stages are never moved backwards. */
    fun afterGenerate(current: String): String = when (current) {
        JobStatus.AWAITING_RESPONSE, JobStatus.REVIEW, JobStatus.COMPLETED -> current
        else -> JobStatus.PREPARED
    }

    /** The user copied the prompt or opened the AI, so we now wait for a pasted reply. */
    fun afterMarkSent(current: String): String = when (current) {
        JobStatus.REVIEW, JobStatus.COMPLETED -> current
        else -> JobStatus.AWAITING_RESPONSE
    }

    /** After the user saves a reviewed reply. */
    fun afterSave(paraCount: Int, translatedNow: Int): String =
        if (translatedNow >= paraCount) JobStatus.COMPLETED else JobStatus.INCOMPLETE

    /** Whether the reader should trust this job's "done" claim: it must match what is really saved. */
    fun reconcile(status: String, paraCount: Int, translatedNow: Int): String = when {
        status == JobStatus.COMPLETED && translatedNow < paraCount -> JobStatus.INCOMPLETE
        status == JobStatus.INCOMPLETE && translatedNow >= paraCount -> JobStatus.COMPLETED
        else -> status
    }

    fun statusText(status: String, missing: Int, error: String?): String = when (status) {
        JobStatus.PENDING -> "Planned. Not prepared or sent."
        JobStatus.PAUSED -> "Paused."
        JobStatus.PREPARED -> "Prompt ready. Not sent to the AI yet."
        JobStatus.AWAITING_RESPONSE -> "Marked as sent. Waiting for the reply you paste back."
        JobStatus.REVIEW -> "Reply read. Waiting for your review."
        JobStatus.COMPLETED -> "Done. All paragraphs translated."
        JobStatus.INCOMPLETE -> "Saved, but $missing paragraph${if (missing == 1) " is" else "s are"} still untranslated."
        JobStatus.FAILED -> "Reply couldn't be read" + (error?.let { ": $it" } ?: ".")
        JobStatus.CANCELLED -> "Cancelled."
        else -> status
    }
}

/** Compact, order-preserving encoding of a list of database ids for storage in one column. */
object IdList {
    fun encode(ids: List<Long>): String = ids.joinToString(",")
    fun decode(s: String?): List<Long> =
        if (s.isNullOrBlank()) emptyList() else s.split(',').mapNotNull { it.trim().toLongOrNull() }
}

/** Decision used by the save transaction so that re-importing the same reply is a no-op. */
object SaveLogic {
    fun isUnchanged(
        oldText: String?, oldStatus: String?, oldTargetLang: String?,
        newText: String, newStatus: String, targetLang: String,
    ): Boolean = oldText != null && oldText == newText && oldStatus == newStatus && oldTargetLang == targetLang

    /**
     * A re-import must never downgrade a translation the user has already reviewed or verified: if the stored
     * status is "better" than the incoming one and the text is identical, keep the stored status.
     */
    fun effectiveStatus(oldText: String?, oldStatus: String?, newText: String, incoming: String): String =
        if (oldText != null && oldText == newText && oldStatus != null && rank(oldStatus) > rank(incoming)) oldStatus else incoming

    private fun rank(status: String) = when (status) {
        "VERIFIED" -> 3
        "TRANSLATED" -> 2
        "NEEDS_REVIEW" -> 1
        else -> 0
    }
}
