package com.punjabibooktranslator

import com.punjabibooktranslator.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4Test {
    private fun term(t: String, tr: String, approved: Boolean = true, type: GlossaryType = GlossaryType.CHARACTER, ex: String? = null) =
        GlossaryTerm(1, t, tr, type, ex, approved)

    // ---- glossary matching
    @Test fun latinTermsMatchWholeWordsIgnoringCase() {
        assertEquals(2, TermMatcher.count("Amrit came. Then AMRIT left.", "amrit"))
        assertEquals(0, TermMatcher.count("Amritsar is far.", "Amrit"))
        assertEquals(1, TermMatcher.count("Amrit's house", "Amrit"))
        assertEquals(1, TermMatcher.count("Mount  Everest here", "Mount Everest"))
    }

    @Test fun gurmukhiTermsMatchAsSubstrings() {
        assertEquals(1, TermMatcher.count("ਅੰਮ੍ਰਿਤ ਨੇ ਕਿਹਾ", "ਅੰਮ੍ਰਿਤ"))
        assertEquals(1, TermMatcher.count("ਅੰਮ੍ਰਿਤਸਰ ਸ਼ਹਿਰ", "ਅੰਮ੍ਰਿਤ"))
        assertEquals(0, TermMatcher.count("ਕੁਝ ਹੋਰ", "ਅੰਮ੍ਰਿਤ"))
        assertEquals(0, TermMatcher.count("text", " "))
    }

    @Test fun onlyApprovedOccurringTermsAreSent() {
        val all = listOf(
            term("Amrit", "ਅੰਮ੍ਰਿਤ"),
            term("Harpreet", "ਹਰਪ੍ਰੀਤ"),
            term("Simran", "ਸਿਮਰਨ", approved = false),
            term("Gurpal", ""),
        )
        val r = GlossaryMatcher.relevant(all, listOf("Amrit met Simran and Gurpal."))
        assertEquals(listOf("Amrit"), r.terms.map { it.term })
    }

    @Test fun relevantIsCappedAndReportsOmitted() {
        val all = (1..10).map { term("Name$it", "x$it") }
        val r = GlossaryMatcher.relevant(all, listOf((1..10).joinToString(" ") { "Name$it" }), max = 4)
        assertEquals(4, r.terms.size); assertEquals(6, r.omitted)
    }

    @Test fun promptLinesCarryTypeAndExplanation() {
        val l = GlossaryMatcher.promptLines(listOf(term("Amrit", "ਅੰਮ੍ਰਿਤ", ex = "the narrator's brother"), term("tea", "ਚਾਹ", type = GlossaryType.GENERAL)))
        assertEquals("Amrit [Character: the narrator's brother]" to "ਅੰਮ੍ਰਿਤ", l[0])
        assertEquals("tea" to "ਚਾਹ", l[1])
    }

    // ---- occurrence checking
    @Test fun termCheckerStates() {
        val rows = listOf(
            OccRow(1, "P0001", 1, "Amrit ran", "ਅੰਮ੍ਰਿਤ ਭੱਜਿਆ"),
            OccRow(2, "P0002", 1, "Amrit sat", "ਅਮ੍ਰਿਤ ਬੈਠਾ"),
            OccRow(3, "P0003", 1, "Amrit sat", "ਕੋਈ ਬੈਠਾ"),
            OccRow(4, "P0004", 1, "Amrit sat", null),
            OccRow(5, "P0005", 1, "Nobody", "x"),
        )
        val res = TermChecker.check(rows, "Amrit", "ਅੰਮ੍ਰਿਤ", "ਅਮ੍ਰਿਤ")
        assertEquals(4, res.size)
        assertEquals(listOf(OccState.HAS_APPROVED, OccState.HAS_OLD, OccState.MISSING, OccState.UNTRANSLATED), res.map { it.state })
        assertTrue(TermChecker.needsReview(OccState.HAS_OLD)); assertFalse(TermChecker.needsReview(OccState.UNTRANSLATED))
    }

    // ---- candidates
    @Test fun candidatesFindRecurringMidSentenceNames() {
        val t = listOf(
            "The day began. Harpreet walked home. Then Harpreet saw Maya.",
            "Maya laughed. She told Harpreet about it. Later Maya left.",
            "The river was cold. Harpreet said nothing to Maya.",
        )
        val c = TermCandidates.find(t).map { it.term }
        assertTrue(c.contains("Harpreet")); assertTrue(c.contains("Maya"))
        assertFalse(c.contains("The")); assertFalse(c.contains("Then"))
    }

    @Test fun candidatesSkipExistingAndRareAndCommonWords() {
        val t = listOf("Go now. Harpreet ran. Harpreet sat. Harpreet slept. Rare met Rare once.", "The bank. bank holiday. In Bank street, Bank is a word.")
        val c = TermCandidates.find(t, existingKeys = setOf("harpreet")).map { it.term }
        assertFalse(c.contains("Harpreet")); assertFalse(c.contains("Rare")); assertFalse(c.contains("Bank"))
    }

    @Test fun candidatesDoNotWorkForGurmukhi() {
        assertTrue(TermCandidates.find(listOf("ਅੰਮ੍ਰਿਤ ਅੰਮ੍ਰਿਤ ਅੰਮ੍ਰਿਤ ਅੰਮ੍ਰਿਤ")).isEmpty())
    }

    @Test fun exampleLimiter() {
        val ex = listOf("a" to "x", "A " to "y", "" to "z", "long".repeat(200) to "q", "b" to "w", "c" to "v", "d" to "u")
        val p = ExampleLimiter.pick(ex, maxCount = 3)
        assertEquals(listOf("a", "b", "c"), p.map { it.first })
    }

    @Test fun promptShowsApprovedExamplesOnlyWhenGiven() {
        val base = PromptSpec(
            bookTitle = "B", author = null, chapterTitle = null, locationLabel = null, sourceLang = "en", targetLang = "pa",
            style = TranslationStyle.LITERARY, blocks = listOf(PromptBlock("P0001", "Hello")),
        )
        assertFalse(PromptBuilder.build(base).contains("PREVIOUSLY APPROVED"))
        val p = PromptBuilder.build(base.copy(approvedExamples = listOf("Hi" to "ਸਤ ਸ੍ਰੀ ਅਕਾਲ")))
        assertTrue(p.contains("PREVIOUSLY APPROVED")); assertTrue(p.contains("- Hi  =>  ਸਤ ਸ੍ਰੀ ਅਕਾਲ"))
    }

    // ---- verification parser
    private val ids = listOf("P0001", "P0002", "P0003")

    @Test fun parsesOkAndIssues() {
        val raw = """```json
{"verification":[
{"id":"P0001","verdict":"ok"},
{"id":"P0002","verdict":"issues","severity":"major","issues":[{"type":"omission","description":"Last sentence missing","explanation":"The original has two sentences"}],"suggested_translation":"ਨਵਾਂ"},
{"id":"P0003","verdict":"ok"}]}
```"""
        val p = VerificationParser.parse(raw, ids)
        assertTrue(p.isComplete); assertEquals(2, p.okCount); assertEquals(1, p.issueCount)
        val f = p.findings["P0002"]!!
        assertEquals(Severity.MAJOR, f.severity); assertEquals("ਨਵਾਂ", f.suggestion); assertEquals("omission", f.issues[0].type)
    }

    @Test fun silenceIsNotVerified() {
        val p = VerificationParser.parse("""{"verification":[{"id":"P0001","verdict":"ok"}]}""", ids)
        assertEquals(listOf("P0002", "P0003"), p.notCovered); assertFalse(p.isComplete)
        assertFalse(p.findings.containsKey("P0002"))
    }

    @Test fun missingSeverityDefaultsToModerateWithWarning() {
        val p = VerificationParser.parse("""[{"id":"P0001","verdict":"issues","issues":[{"description":"odd"}]}]""", ids)
        assertEquals(Severity.MODERATE, p.findings["P0001"]!!.severity)
        assertTrue(p.warnings.any { it.contains("severity") })
    }

    @Test fun entryWithoutVerdictOrContentStaysUnverified() {
        val p = VerificationParser.parse("""[{"id":"P0001","note":"x"},{"id":"P0002","verdict":"ok"}]""", ids)
        assertFalse(p.findings.containsKey("P0001"))
    }

    @Test fun duplicateEntriesMerge() {
        val raw = """[{"id":"P0001","verdict":"issues","severity":"minor","issues":[{"description":"a"}]},
{"id":"P0001","verdict":"issues","severity":"major","issues":[{"description":"b"}]}]"""
        val f = VerificationParser.parse(raw, ids).findings["P0001"]!!
        assertEquals(Severity.MAJOR, f.severity); assertEquals(2, f.issues.size)
    }

    @Test fun unknownIdsIgnoredAndReported() {
        val p = VerificationParser.parse("""[{"id":"P0099","verdict":"ok"},{"id":"p0001","verdict":"ok"}]""", ids)
        assertEquals(listOf("P0099"), p.unknown); assertTrue(p.findings.containsKey("P0001"))
    }

    @Test fun truncatedReplyKeepsCompleteEntries() {
        val raw = "```json\n{\"verification\":[{\"id\":\"P0001\",\"verdict\":\"ok\"},{\"id\":\"P0002\",\"verdict\":\"issues\",\"severity\":\"mi"
        val p = VerificationParser.parse(raw, ids)
        assertTrue(p.truncated); assertTrue(p.recovered); assertNull(p.error)
        assertEquals(setOf("P0001"), p.findings.keys); assertEquals(listOf("P0002", "P0003"), p.notCovered)
    }

    @Test fun garbageAndEmptyAreErrors() {
        assertNotNull(VerificationParser.parse("", ids).error)
        val g = VerificationParser.parse("Sure! Everything looks fine.", ids)
        assertNotNull(g.error); assertEquals(ids, g.notCovered)
    }

    @Test fun repairPromptForbidsChangesAndListsIds() {
        val r = VerifyPromptBuilder.buildRepair(ids, "broken", "cut off")
        assertTrue(r.contains("WITHOUT changing")); assertTrue(r.contains("P0003")); assertTrue(r.contains("broken"))
    }

    @Test fun verifyPromptContainsPairsAndRules() {
        val p = VerifyPromptBuilder.build(
            VerifySpec("T", null, "Ch1", null, "en", "pa", listOf(VerifyItem("P0001", "Hi", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ")), glossary = listOf("Amrit" to "ਅੰਮ੍ਰਿਤ"))
        )
        assertTrue(p.contains("\"id\":\"P0001\"")); assertTrue(p.contains("ਸਤ ਸ੍ਰੀ ਅਕਾਲ")); assertTrue(p.contains("GLOSSARY"))
        assertTrue(p.contains("Include exactly 1 entries"))
    }

    // ---- corrections / state
    @Test fun suggestionSameAsCurrentIsNotACorrection() {
        assertNull(Corrections.effectiveSuggestion("ਇਹ  ਹੈ", "ਇਹ ਹੈ")); assertNull(Corrections.effectiveSuggestion("a", "  "))
        assertEquals("b", Corrections.effectiveSuggestion("a", " b "))
    }

    @Test fun resolveActions() {
        assertEquals(Resolve.Result("new", FindingStates.ACCEPTED), Resolve.apply(Resolve.ACCEPT, "old", "new", null))
        assertNull(Resolve.apply(Resolve.ACCEPT, "old", null, null))
        assertNull(Resolve.apply(Resolve.ACCEPT, "old", "old", null))
        assertEquals(Resolve.Result("mine", FindingStates.EDITED), Resolve.apply(Resolve.EDIT, "old", "new", "mine"))
        assertNull(Resolve.apply(Resolve.EDIT, "old", "new", "old"))
        assertEquals(Resolve.Result(null, FindingStates.REJECTED), Resolve.apply(Resolve.REJECT, "old", "new", null))
    }

    @Test fun stateAfterResolution() {
        assertEquals(VerifyStates.VERIFIED, Resolve.stateAfter(Resolve.ACCEPT, listOf("MAJOR")))
        assertEquals(VerifyStates.VERIFIED, Resolve.stateAfter(Resolve.REJECT, emptyList()))
        assertEquals(VerifyStates.MAJOR_ISSUES, Resolve.stateAfter(Resolve.REJECT, listOf("minor", "major")))
        assertEquals(VerifyStates.MAJOR_ISSUES, VerifyStates.flagged(VerifyStates.MAJOR_ISSUES))
        assertEquals(VerifyStates.NEEDS_REVIEW, VerifyStates.flagged(VerifyStates.VERIFIED))
    }

    // ---- planner + fingerprint
    @Test fun plannerSkipsStaleAndUnmentioned() {
        val t1 = VerifyTarget(1, "P0001", 11, "text one")
        val t2 = VerifyTarget(2, "P0002", 12, "text two")
        val t3 = VerifyTarget(3, "P0003", 13, "text three")
        val snap = Fingerprint.decode(Fingerprint.encode(mapOf(1L to "text one", 2L to "OLD two", 3L to "text three")))
        val parse = VerificationParser.parse(
            """[{"id":"P0001","verdict":"ok"},{"id":"P0002","verdict":"ok"},{"id":"P0003","verdict":"issues","severity":"minor","issues":[{"description":"d"}],"suggested_translation":"text three"}]""",
            listOf("P0001", "P0002", "P0003"),
        )
        val plan = VerifyPlanner.plan(listOf(t1, t2, t3), parse, snap)
        assertEquals(listOf("P0002"), plan.stale)
        assertEquals(VerifyStates.VERIFIED, plan.outcomes.first { it.target.paraId == "P0001" }.newState)
        val o3 = plan.outcomes.first { it.target.paraId == "P0003" }
        assertEquals(VerifyStates.MINOR_ISSUES, o3.newState); assertNull(o3.finding!!.suggestion)
        val partial = VerifyPlanner.plan(listOf(t1, t2), VerificationParser.parse("""[{"id":"P0001","verdict":"ok"}]""", listOf("P0001", "P0002")), snap)
        assertEquals(1, partial.outcomes.size)
    }

    @Test fun fingerprintRoundTripAndMismatch() {
        val s = Fingerprint.decode(Fingerprint.encode(mapOf(5L to "ਕੁਝ")))
        assertTrue(Fingerprint.matches(s, 5, "ਕੁਝ")); assertFalse(Fingerprint.matches(s, 5, "ਹੋਰ")); assertFalse(Fingerprint.matches(s, 6, "ਕੁਝ"))
        assertTrue(Fingerprint.decode(null).isEmpty()); assertTrue(Fingerprint.decode("garbage").isEmpty())
    }

    // ---- summaries
    @Test fun summaryParserCases() {
        assertEquals("Ok text.", SummaryParser.parse("```json\n{\"summary\":\"Ok text.\"}\n```").text)
        val cut = SummaryParser.parse("{\"summary\":\"Half a sent")
        assertNotNull(cut.error); assertEquals("", cut.text)
        val plain = SummaryParser.parse("Summary: Just prose here.")
        assertEquals("Just prose here.", plain.text); assertTrue(plain.warnings.isNotEmpty())
        assertNotNull(SummaryParser.parse("").error)
        assertNotNull(SummaryParser.parse("{\"other\":1}").error)
    }

    @Test fun summaryPromptMentionsPreviousPart() {
        val p = SummaryPromptBuilder.build("B", null, "C", "en", listOf("one", "two"), 1, 3, "Earlier.")
        assertTrue(p.contains("PART: 2 of 3")); assertTrue(p.contains("EXISTING SUMMARY")); assertTrue(p.contains("one\n\ntwo"))
        assertFalse(SummaryPromptBuilder.build("B", null, null, "en", listOf("x"), 0, 1, null).contains("EXISTING SUMMARY"))
    }

    @Test fun summaryPlannerKeepsEverythingInOrder() {
        val items = (1..10).map { "p$it" to 900 }
        val parts = SummaryPlanner.split(items, { it.second }, budget = 2000)
        assertEquals(items, parts.flatten()); assertTrue(parts.all { it.size <= 2 })
        assertEquals(1, SummaryPlanner.split(listOf("x" to 50_000), { it.second }).size)
        assertTrue(SummaryPlanner.split(emptyList<Int>(), { it }).isEmpty())
    }

    // ---- search
    @Test fun searchEscapingAndSnippets() {
        assertEquals("%50\\%\\_a\\\\%", SearchText.pattern("50%_a\\"))
        assertEquals(3, SearchText.indexOfIgnoreCase("xxABC", "abc") + 1)
        val s = Snippets.around("a".repeat(100) + " Needle " + "b".repeat(100), "needle", 10)
        assertTrue(s.text.startsWith("…")); assertTrue(s.text.endsWith("…"))
        assertEquals("Needle", s.text.substring(s.matchStart, s.matchEnd))
        assertEquals(-1, Snippets.around("abc", "zzz").matchStart)
    }

    // ---- save engine revisions
    @Test fun saveKeepsRevisionOnlyWhenTextChangesAndResetsVerification() {
        val old = StoredTranslation(7, "old text", "TRANSLATED", "pa", 1L, 1, false, VerifyStates.VERIFIED)
        val same = SaveEngine.plan(mapOf(1L to old), listOf(SaveInput(1, "old text", false, false)), "pa", 5L)
        assertTrue(same.writes.isEmpty())
        val changed = SaveEngine.plan(mapOf(1L to old), listOf(SaveInput(1, "new text", false, false)), "pa", 5L)
        val w = changed.writes.single()
        assertEquals("old text", w.previousText); assertEquals(VerifyStates.NOT_VERIFIED, w.verifyState)
        val fresh = SaveEngine.plan(emptyMap(), listOf(SaveInput(2, "x", false, false)), "pa", 5L).writes.single()
        assertNull(fresh.previousText); assertEquals(VerifyStates.NOT_VERIFIED, fresh.verifyState)
    }

    @Test fun savedTranslationIsNormalised() {
        val composed = "\u00E9"
        val decomposed = "e\u0301"
        val w = SaveEngine.plan(emptyMap(), listOf(SaveInput(1, "  $decomposed  ", false, false)), "pa", 1L).writes.single()
        assertEquals(composed, w.text)
    }
}
