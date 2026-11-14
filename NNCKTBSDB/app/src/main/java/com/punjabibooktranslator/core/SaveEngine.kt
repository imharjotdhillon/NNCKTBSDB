package com.punjabibooktranslator.core

data class StoredTranslation(
    val id: Long, val text: String, val status: String, val targetLang: String,
    val createdAt: Long, val revision: Int, val userApproved: Boolean,
    val verifyState: String = "NOT_VERIFIED",
)

data class SaveInput(val blockId: Long, val text: String, val flagged: Boolean, val edited: Boolean)

data class SaveWrite(
    val blockId: Long, val existingId: Long, val text: String, val status: String,
    val createdAt: Long, val revision: Int, val userApproved: Boolean,
    /** The text being replaced, when an existing translation's text really changes (a revision must be kept). */
    val previousText: String? = null,
    /** Any earlier verification result no longer applies to the new text. */
    val verifyState: String = "NOT_VERIFIED",
)

data class SaveOutcome(val writes: List<SaveWrite>, val unchanged: Int, val skippedBlank: Int)

/**
 * Pure decision logic for saving reviewed translations. The repository runs it inside one database transaction
 * and only applies [SaveOutcome.writes]. Properties (all unit-tested):
 *  - idempotent: identical input produces no writes the second time;
 *  - blank entries are skipped, never written, so they cannot overwrite an existing translation;
 *  - only paragraphs present in [inputs] are ever touched;
 *  - a re-import with identical text never downgrades VERIFIED/TRANSLATED to NEEDS_REVIEW.
 */
object SaveEngine {
    fun plan(existing: Map<Long, StoredTranslation>, inputs: List<SaveInput>, targetLang: String, now: Long): SaveOutcome {
        val writes = ArrayList<SaveWrite>()
        var unchanged = 0
        var blank = 0
        val seen = HashSet<Long>()
        for (item in inputs) {
            if (!seen.add(item.blockId)) continue // the same paragraph twice in one save: first one wins
            val text = TextUtils.normalizeTranslation(item.text)
            if (text.isEmpty()) { blank++; continue }
            val old = existing[item.blockId]
            val incoming = if (item.flagged && !item.edited) "NEEDS_REVIEW" else "TRANSLATED"
            val status = SaveLogic.effectiveStatus(old?.text, old?.status, text, incoming)
            val approvalChanges = item.edited && old?.userApproved == false
            if (old != null && !approvalChanges &&
                SaveLogic.isUnchanged(old.text, old.status, old.targetLang, text, status, targetLang)
            ) { unchanged++; continue }
            val textChanged = old != null && old.text != text
            writes.add(
                SaveWrite(
                    item.blockId, old?.id ?: 0, text, status, old?.createdAt ?: now,
                    (old?.revision ?: 0) + 1, item.edited || (old?.userApproved ?: false),
                    previousText = if (textChanged) old?.text else null,
                    verifyState = if (old == null || textChanged) "NOT_VERIFIED" else old.verifyState,
                )
            )
        }
        return SaveOutcome(writes, unchanged, blank)
    }
}
