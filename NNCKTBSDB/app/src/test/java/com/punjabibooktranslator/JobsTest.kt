package com.punjabibooktranslator

import com.punjabibooktranslator.core.BookProgress
import com.punjabibooktranslator.core.IdList
import com.punjabibooktranslator.core.JobRules
import com.punjabibooktranslator.core.JobStatus
import com.punjabibooktranslator.core.ProgressCalc
import com.punjabibooktranslator.core.QueueGroup
import com.punjabibooktranslator.core.SaveLogic
import com.punjabibooktranslator.core.UnitAgg
import com.punjabibooktranslator.core.UnitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsTest {
    private val all = listOf(
        JobStatus.PENDING, JobStatus.PAUSED, JobStatus.PREPARED, JobStatus.AWAITING_RESPONSE, JobStatus.REVIEW,
        JobStatus.COMPLETED, JobStatus.INCOMPLETE, JobStatus.FAILED, JobStatus.CANCELLED,
    )

    @Test fun everyStatusHasAGroupAndNothingClaimsToBeRunning() {
        for (s in all) { JobRules.group(s); assertFalse(JobRules.statusText(s, 2, "x").contains("translating", ignoreCase = true)) }
        assertEquals(QueueGroup.entries.toSet(), JobRules.groupOrder.toSet())
        assertEquals(QueueGroup.AWAITING, JobRules.group(JobStatus.AWAITING_RESPONSE))
        assertEquals(QueueGroup.PROBLEM, JobRules.group(JobStatus.FAILED))
        assertEquals(QueueGroup.PROBLEM, JobRules.group(JobStatus.INCOMPLETE))
    }

    @Test fun promptPreparedIsNotAwaitingUntilUserSendsIt() {
        assertEquals(JobStatus.PREPARED, JobRules.afterGenerate(JobStatus.PENDING))
        assertEquals(JobStatus.AWAITING_RESPONSE, JobRules.afterMarkSent(JobStatus.PREPARED))
        // regenerating a prompt must not drag a job backwards
        assertEquals(JobStatus.AWAITING_RESPONSE, JobRules.afterGenerate(JobStatus.AWAITING_RESPONSE))
        assertEquals(JobStatus.REVIEW, JobRules.afterGenerate(JobStatus.REVIEW))
        assertEquals(JobStatus.COMPLETED, JobRules.afterMarkSent(JobStatus.COMPLETED))
        assertEquals(JobStatus.REVIEW, JobRules.afterMarkSent(JobStatus.REVIEW))
    }

    @Test fun saveOutcomeFollowsWhatIsReallySaved() {
        assertEquals(JobStatus.COMPLETED, JobRules.afterSave(5, 5))
        assertEquals(JobStatus.INCOMPLETE, JobRules.afterSave(5, 3))     // partial response
        assertEquals(JobStatus.INCOMPLETE, JobRules.afterSave(5, 0))
        assertEquals(JobStatus.INCOMPLETE, JobRules.reconcile(JobStatus.COMPLETED, 5, 4))
        assertEquals(JobStatus.COMPLETED, JobRules.reconcile(JobStatus.INCOMPLETE, 5, 5))
        assertEquals(JobStatus.AWAITING_RESPONSE, JobRules.reconcile(JobStatus.AWAITING_RESPONSE, 5, 0))
    }

    @Test fun allowedActionsPerStatus() {
        assertTrue(JobRules.canPause(JobStatus.PENDING)); assertFalse(JobRules.canPause(JobStatus.AWAITING_RESPONSE))
        assertTrue(JobRules.canResume(JobStatus.PAUSED)); assertFalse(JobRules.canResume(JobStatus.PENDING))
        assertTrue(JobRules.canRetry(JobStatus.FAILED)); assertTrue(JobRules.canRetry(JobStatus.INCOMPLETE))
        assertFalse(JobRules.canRetry(JobStatus.COMPLETED)); assertFalse(JobRules.canCancel(JobStatus.COMPLETED))
        assertTrue(JobRules.needsConfirmToRemove(JobStatus.REVIEW)); assertFalse(JobRules.needsConfirmToRemove(JobStatus.PENDING))
        assertEquals("Saved, but 1 paragraph is still untranslated.", JobRules.statusText(JobStatus.INCOMPLETE, 1, null))
    }

    @Test fun idListRoundTripsInOrder() {
        val ids = listOf(5L, 3L, 900000000000L, 7L)
        assertEquals(ids, IdList.decode(IdList.encode(ids)))
        assertEquals(emptyList<Long>(), IdList.decode(null)); assertEquals(emptyList<Long>(), IdList.decode(""))
    }

    @Test fun repeatedImportIsAnIdempotentNoOp() {
        assertTrue(SaveLogic.isUnchanged("ਪਾਠ", "TRANSLATED", "pa", "ਪਾਠ", "TRANSLATED", "pa"))
        assertFalse(SaveLogic.isUnchanged("ਪਾਠ", "TRANSLATED", "pa", "ਹੋਰ", "TRANSLATED", "pa"))
        assertFalse(SaveLogic.isUnchanged(null, null, null, "x", "TRANSLATED", "pa"))
        // re-importing identical text must not downgrade reviewed/verified work
        assertEquals("VERIFIED", SaveLogic.effectiveStatus("a", "VERIFIED", "a", "NEEDS_REVIEW"))
        assertEquals("TRANSLATED", SaveLogic.effectiveStatus("a", "TRANSLATED", "a", "NEEDS_REVIEW"))
        assertEquals("NEEDS_REVIEW", SaveLogic.effectiveStatus("a", "VERIFIED", "different", "NEEDS_REVIEW"))
        assertEquals("TRANSLATED", SaveLogic.effectiveStatus(null, null, "a", "TRANSLATED"))
    }

    // ---- accurate progress ----
    private fun unit(id: Long, total: Int, tr: Int, rev: Int = 0, ver: Int = 0) = UnitAgg(id, "Page $id", (id * 10).toInt(), total, tr, rev, ver)

    @Test fun pageWithTenOfTwelveIsPartialNotComplete() {
        val u = unit(1, 12, 10)
        assertEquals(UnitState.PARTIAL, ProgressCalc.state(u))
        val p = ProgressCalc.compute(listOf(u))
        assertEquals(1, p.partialUnits); assertEquals(0, p.fullUnits); assertEquals(2, p.missingBlocks)
    }

    @Test fun bookPercentComesFromParagraphsNotPages() {
        // page 1 has 1 of 100 translated; page 2 is fully translated (1 paragraph). A page-based figure would say 50%.
        val p = ProgressCalc.compute(listOf(unit(1, 100, 1), unit(2, 1, 1)))
        assertEquals(2, p.translatedBlocks); assertEquals(101, p.totalBlocks)
        assertEquals(1, p.percent)                                       // floor(2/101)
        assertEquals(1, p.fullUnits); assertEquals(1, p.partialUnits)
    }

    @Test fun hundredPercentOnlyWhenEveryParagraphIsDone() {
        assertEquals(99, ProgressCalc.percent(999, 1000)); assertEquals(100, ProgressCalc.percent(1000, 1000))
        assertEquals(0, ProgressCalc.percent(0, 0)); assertEquals(0, ProgressCalc.percent(0, 50))
        assertEquals(99, ProgressCalc.percent(9999, 10000))
    }

    @Test fun totalsAddUp() {
        val units = listOf(unit(1, 10, 10, ver = 10), unit(2, 10, 6, rev = 3), unit(3, 10, 0), unit(4, 5, 5, rev = 1))
        val p: BookProgress = ProgressCalc.compute(units)
        assertEquals(4, p.totalUnits)
        assertEquals(p.totalUnits, p.fullUnits + p.partialUnits + p.untranslatedUnits)
        assertEquals(2, p.fullUnits); assertEquals(1, p.partialUnits); assertEquals(1, p.untranslatedUnits)
        assertEquals(2, p.unitsAwaitingReview); assertEquals(1, p.unitsFullyVerified)
        assertEquals(35, p.totalBlocks); assertEquals(21, p.translatedBlocks); assertEquals(14, p.missingBlocks)
        assertEquals(listOf(2L, 3L), p.incompleteUnits.map { it.unitId })       // navigation targets, in reading order
        assertEquals(listOf(2L, 4L), p.reviewUnits.map { it.unitId })
    }
}
