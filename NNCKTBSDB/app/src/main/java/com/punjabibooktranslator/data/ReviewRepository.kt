package com.punjabibooktranslator.data

import androidx.room.withTransaction
import com.punjabibooktranslator.core.Fingerprint
import com.punjabibooktranslator.core.FindingStates
import com.punjabibooktranslator.core.OutcomeFinding
import com.punjabibooktranslator.core.Resolve
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.core.VerifyParse
import com.punjabibooktranslator.core.VerifyPlanner
import com.punjabibooktranslator.core.VerifyStates
import com.punjabibooktranslator.core.VerifyTarget
import kotlinx.coroutines.flow.Flow

data class VerifyApplied(val verified: Int, val withIssues: Int, val stale: List<String>, val notCovered: Int)

class ReviewRepository(private val db: AppDatabase) {

    fun observeOpenFindings(bookId: Long): Flow<List<FindingRow>> = db.findingDao().observeOpen(bookId)
    fun observeOpenCount(bookId: Long): Flow<Int> = db.findingDao().observeOpenCount(bookId)
    suspend fun revisions(blockId: Long): List<RevisionEntity> = db.revisionDao().forBlock(blockId)

    /** Replaces the text of an existing translation, keeping the old text as a revision. Returns false if nothing changed. */
    private suspend fun replaceText(blockId: Long, newText: String, reason: String, supersede: Boolean): Boolean {
        val reader = db.readerDao()
        val old = reader.translationsFor(listOf(blockId)).firstOrNull() ?: return false
        val text = TextUtils.normalizeTranslation(newText)
        if (text.isEmpty() || text == old.translatedText) return false
        val now = System.currentTimeMillis()
        db.revisionDao().insert(RevisionEntity(textBlockId = blockId, translatedText = old.translatedText, revision = old.revision, reason = reason, createdAt = now))
        if (supersede) db.findingDao().supersedeOpen(blockId, now)
        reader.updateTranslation(
            old.copy(
                translatedText = text, status = "TRANSLATED", updatedAt = now, revision = old.revision + 1,
                userApproved = true, verifyState = VerifyStates.NOT_VERIFIED,
            )
        )
        return true
    }

    /** A manual edit from the reader. Blank text is refused so an edit can never wipe a translation. */
    suspend fun edit(blockId: Long, newText: String): Boolean = db.withTransaction { replaceText(blockId, newText, "EDIT", true) }

    suspend fun revert(blockId: Long, revisionId: Long): Boolean = db.withTransaction {
        val rev = db.revisionDao().get(revisionId)
        if (rev == null || rev.textBlockId != blockId) false else replaceText(blockId, rev.translatedText, "REVERT", true)
    }

    /**
     * Applies a pasted verification reply. Re-checks inside the transaction that each paragraph's translation is still the
     * text the prompt was built from; changed paragraphs are skipped and reported. Paragraphs the AI did not cover are untouched.
     */
    suspend fun applyVerification(blockIds: List<Long>, parse: VerifyParse, fingerprint: String): VerifyApplied = db.withTransaction {
        val reader = db.readerDao()
        val rows = blockIds.chunked(500).flatMap { reader.blocksByIds(it) }
        val targets = rows.filter { it.translatedText != null && it.translationId != null }
            .map { VerifyTarget(it.block.id, it.block.paraId, it.translationId!!, it.translatedText!!) }
        val plan = VerifyPlanner.plan(targets, parse, Fingerprint.decode(fingerprint))
        val now = System.currentTimeMillis()
        var verified = 0
        var issues = 0
        for (o in plan.outcomes) {
            val blockId = o.target.blockId
            db.findingDao().supersedeOpen(blockId, now)
            reader.setVerifyState(blockId, o.newState)
            val f: OutcomeFinding? = o.finding
            if (f == null) verified++ else {
                issues++
                db.findingDao().insert(
                    FindingEntity(
                        bookId = rows.first { it.block.id == blockId }.block.bookId, textBlockId = blockId, severity = f.severity.name,
                        issueText = f.issueText, explanation = f.explanation, suggestion = f.suggestion,
                        state = FindingStates.OPEN, createdAt = now, resolvedAt = null,
                    )
                )
            }
        }
        VerifyApplied(verified, issues, plan.stale, parse.notCovered.size)
    }

    /** Accept the suggestion, edit it, or reject the finding. Nothing changes without the user choosing. */
    suspend fun resolve(findingId: Long, action: String, editedText: String? = null): Boolean = db.withTransaction {
        val finding = db.findingDao().get(findingId) ?: return@withTransaction false
        if (finding.state != FindingStates.OPEN) return@withTransaction false
        val reader = db.readerDao()
        val tr = reader.translationsFor(listOf(finding.textBlockId)).firstOrNull() ?: return@withTransaction false
        val result = Resolve.apply(action, tr.translatedText, finding.suggestion, editedText) ?: return@withTransaction false
        val now = System.currentTimeMillis()
        if (result.newText != null) {
            replaceText(finding.textBlockId, result.newText, if (action == Resolve.ACCEPT) "ACCEPTED_SUGGESTION" else "EDIT", supersede = false)
        }
        db.findingDao().setState(findingId, result.findingState, now)
        val remaining = db.findingDao().openSeverities(finding.textBlockId)
        reader.setVerifyState(finding.textBlockId, Resolve.stateAfter(action, remaining))
        true
    }
}
