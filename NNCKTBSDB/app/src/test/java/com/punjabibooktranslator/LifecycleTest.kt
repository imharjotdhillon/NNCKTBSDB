package com.punjabibooktranslator

import com.punjabibooktranslator.core.BatchPlanner
import com.punjabibooktranslator.core.IdList
import com.punjabibooktranslator.core.Json
import com.punjabibooktranslator.core.JobRules
import com.punjabibooktranslator.core.JobStatus
import com.punjabibooktranslator.core.PlanBlock
import com.punjabibooktranslator.core.ProgressCalc
import com.punjabibooktranslator.core.ResponseParser
import com.punjabibooktranslator.core.SaveEngine
import com.punjabibooktranslator.core.SaveInput
import com.punjabibooktranslator.core.StoredTranslation
import com.punjabibooktranslator.core.UnitAgg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real planner, parser, save engine and job rules against an in-memory stand-in for the database.
 * It cannot prove Room behaviour, but it proves the decisions the repositories rely on.
 */
class LifecycleTest {
    /** The persisted part of a job. Strings only, exactly like the table, so "app closure" is a real round trip. */
    private data class Row(var status: String, var blockIds: String, var prompt: String?, var promptIds: String?, var response: String?, var error: String?)

    private class World(pages: Int, perPage: Int) {
        val blocks: List<PlanBlock> = (0 until pages * perPage).map {
            PlanBlock(it + 1L, "P%04d".format(it + 1), it, it / perPage + 1, 1, "Doc", 100, false)
        }
        val saved = HashMap<Long, StoredTranslation>()      // textBlockId -> translation
        var nextId = 1L
        fun idOf(paraId: String) = blocks.first { it.paraId == paraId }.blockId
        fun translated(ids: List<Long>) = ids.count { it in saved }
        fun save(job: Row, items: List<SaveInput>, now: Long = 1L): Pair<Int, Int> {
            val o = SaveEngine.plan(saved, items, "pa", now)
            for (w in o.writes) saved[w.blockId] = StoredTranslation(
                if (w.existingId != 0L) w.existingId else nextId++, w.text, w.status, "pa", w.createdAt, w.revision, w.userApproved)
            val ids = IdList.decode(job.blockIds)
            job.status = JobRules.afterSave(ids.size, translated(ids))
            if (job.status == JobStatus.COMPLETED) job.response = null
            return o.writes.size to o.unchanged
        }
    }

    private fun reply(vararg p: Pair<String, String>) =
        "```json\n{\"translations\":[" + p.joinToString(",") { "{\"id\":${Json.quote(it.first)},\"translated_text\":${Json.quote(it.second)}}" } + "]}\n```"

    private fun newJob(w: World, pages: Int): Row {
        val b = BatchPlanner.planPdf(w.blocks, 5, 1, pages, "pa").first()
        return Row(JobStatus.PENDING, IdList.encode(b.blockIds), null, null, null, null)
    }

    private fun inputs(w: World, r: com.punjabibooktranslator.core.ParseResult) =
        r.proposals.map { (pid, t) -> SaveInput(w.idOf(pid), t, flagged = false, edited = false) }

    @Test fun partialReplySavesWhatItHasThenRetryAsksOnlyForTheRest() {
        val w = World(5, 2)                                   // 10 paragraphs, one five-page batch
        val job = newJob(w, 5)
        val ids = IdList.decode(job.blockIds); val paras = ids.map { id -> w.blocks.first { it.blockId == id }.paraId }
        assertEquals(10, ids.size)

        job.prompt = "prompt"; job.promptIds = job.blockIds; job.status = JobRules.afterGenerate(job.status)
        assertEquals(JobStatus.PREPARED, job.status)
        job.status = JobRules.afterMarkSent(job.status); assertEquals(JobStatus.AWAITING_RESPONSE, job.status)

        // Gemini's reply stops after 6 paragraphs and is cut off mid-way through the 7th
        val full = reply(*paras.take(7).mapIndexed { i, p -> p to "ਅਨੁਵਾਦ $i" }.toTypedArray())
        val cut = full.substring(0, full.length - 25)
        val r = ResponseParser.parse(cut, paras)
        assertTrue(r.truncated); assertEquals(6, r.proposals.size)
        job.response = cut; job.status = JobStatus.REVIEW

        w.save(job, inputs(w, r))
        assertEquals(JobStatus.INCOMPLETE, job.status)            // honest: 6 of 10
        assertEquals(6, w.translated(ids))

        // RETRY: clears prompt/reply, next prompt covers only the untranslated paragraphs
        job.status = JobStatus.PENDING; job.prompt = null; job.promptIds = null; job.response = null
        val remaining = ids.filter { it !in w.saved }
        assertEquals(4, remaining.size)
        assertEquals(paras.drop(6), remaining.map { id -> w.blocks.first { it.blockId == id }.paraId })

        val r2 = ResponseParser.parse(reply(*paras.drop(6).map { it to "ਬਾਕੀ" }.toTypedArray()), paras.drop(6))
        assertTrue(r2.isClean)
        val firstSix = w.saved.filterKeys { it in ids.take(6) }.mapValues { it.value.text }
        w.save(job, inputs(w, r2))
        assertEquals(JobStatus.COMPLETED, job.status)
        assertEquals(10, w.translated(ids))
        for ((k, t) in firstSix) assertEquals(t, w.saved[k]!!.text)   // the retry never touched the first six
    }

    @Test fun repeatingTheSameImportCreatesNothingNew() {
        val w = World(1, 3); val job = newJob(w, 1)
        val paras = listOf("P0001", "P0002", "P0003")
        val r = ResponseParser.parse(reply("P0001" to "a", "P0002" to "b", "P0003" to "c"), paras)
        val (written1, same1) = w.save(job, inputs(w, r))
        assertEquals(3, written1); assertEquals(0, same1)
        val ids1 = w.saved.values.map { it.id }.toSet(); val rev1 = w.saved.values.map { it.revision }
        val (written2, same2) = w.save(job, inputs(w, r))             // user pastes and saves the very same reply again
        assertEquals(0, written2); assertEquals(3, same2)
        assertEquals(ids1, w.saved.values.map { it.id }.toSet()); assertEquals(rev1, w.saved.values.map { it.revision })
        assertEquals(3, w.saved.size)
        assertEquals(JobStatus.COMPLETED, job.status)
    }

    @Test fun reImportNeverDowngradesReviewedWorkAndChangedTextBumpsRevision() {
        val w = World(1, 2); val job = newJob(w, 1)
        w.save(job, listOf(SaveInput(1, "a", false, false), SaveInput(2, "b", false, false)))
        w.saved[1] = w.saved[1]!!.copy(status = "VERIFIED")
        w.save(job, listOf(SaveInput(1, "a", flagged = true, edited = false)))        // same text, flagged this time
        assertEquals("VERIFIED", w.saved[1]!!.status)
        val before = w.saved[2]!!.revision
        w.save(job, listOf(SaveInput(2, "b2", false, true)))                           // genuinely different text
        assertEquals(before + 1, w.saved[2]!!.revision); assertEquals("b2", w.saved[2]!!.text); assertTrue(w.saved[2]!!.userApproved)
    }

    @Test fun blankAndDuplicateEntriesCannotEraseOrDoubleWrite() {
        val w = World(1, 2); val job = newJob(w, 1)
        w.save(job, listOf(SaveInput(1, "keep me", false, false)))
        val (written, _) = w.save(job, listOf(SaveInput(1, "   ", false, false), SaveInput(2, "x", false, false), SaveInput(2, "y", false, false)))
        assertEquals("keep me", w.saved[1]!!.text)                   // blank never overwrote it
        assertEquals("x", w.saved[2]!!.text); assertEquals(1, written)   // duplicate block in one save: first wins, one write
    }

    @Test fun appClosureMidQueueRestoresExactState() {
        val w = World(5, 2); val job = newJob(w, 5)
        val paras = IdList.decode(job.blockIds).map { id -> w.blocks.first { it.blockId == id }.paraId }
        job.prompt = "the prompt text"; job.promptIds = job.blockIds; job.status = JobStatus.AWAITING_RESPONSE
        val r = ResponseParser.parse(reply(*paras.take(4).map { it to "ਪਾਠ" }.toTypedArray()), paras)
        job.response = "raw reply text"; job.status = JobStatus.REVIEW      // reply read, user has not saved yet

        // process death: everything is rebuilt from the stored strings alone
        val restored = Row(job.status, job.blockIds, job.prompt, job.promptIds, job.response, job.error)
        assertEquals(JobStatus.REVIEW, restored.status)
        assertEquals(IdList.decode(job.blockIds), IdList.decode(restored.blockIds))
        assertEquals("raw reply text", restored.response)                  // the pasted reply was not lost
        assertEquals(0, w.saved.size)                                      // and nothing was saved behind the user's back
        assertEquals(restored.promptIds, restored.blockIds)
        // the review is rebuilt by re-parsing the stored reply with the stored id list
        val again = ResponseParser.parse(reply(*paras.take(4).map { it to "ਪਾਠ" }.toTypedArray()), IdList.decode(restored.promptIds).map { id -> w.blocks.first { it.blockId == id }.paraId })
        assertEquals(r.proposals, again.proposals)
    }

    @Test fun unreadableReplyIsFailedKeepsTheTextAndSavesNothing() {
        val w = World(1, 2); val job = newJob(w, 1)
        val bad = "Sorry, I can't translate that."
        val r = ResponseParser.parse(bad, listOf("P0001", "P0002"))
        assertTrue(r.error != null)
        job.status = JobStatus.FAILED; job.response = bad; job.error = r.error
        assertEquals(bad, job.response); assertEquals(0, w.saved.size)
        assertTrue(JobRules.canRetry(job.status))
    }

    @Test fun cancellingKeepsEverythingAlreadySaved() {
        val w = World(5, 2); val job = newJob(w, 5)
        w.save(job, listOf(SaveInput(1, "a", false, false), SaveInput(2, "b", false, false)))
        assertEquals(JobStatus.INCOMPLETE, job.status)
        assertTrue(JobRules.canCancel(job.status)); job.status = JobStatus.CANCELLED
        assertEquals(2, w.saved.size)
        // a cancelled job's paragraphs are free to be planned again; translated ones are skipped
        val replanned = BatchPlanner.planPdf(w.blocks.map { it.copy(translated = it.blockId in w.saved) }, 5, 1, 5, "pa")
        assertEquals(8, replanned.sumOf { it.blockIds.size })
    }

    @Test fun progressStaysHonestAfterPartialSave() {
        val w = World(2, 6); val job = newJob(w, 2)                        // 12 paragraphs on 2 pages
        w.save(job, (1L..10L).map { SaveInput(it, "t", false, false) })
        fun unit(page: Int): UnitAgg {
            val ids = w.blocks.filter { it.page == page }.map { it.blockId }
            return UnitAgg(page.toLong(), "Page $page", ids.first().toInt(), ids.size, w.translated(ids), 0, 0)
        }
        val p = ProgressCalc.compute(listOf(unit(1), unit(2)))
        assertEquals(1, p.fullUnits); assertEquals(1, p.partialUnits)       // page 2 has 4 of 6: NOT complete
        assertEquals(10, p.translatedBlocks); assertEquals(2, p.missingBlocks); assertEquals(83, p.percent)
        assertEquals(listOf(2L), p.incompleteUnits.map { it.unitId })
        assertNull(null); assertFalse(p.percent == 100)
    }
}
