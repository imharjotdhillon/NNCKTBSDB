package com.punjabibooktranslator

import com.punjabibooktranslator.core.Json
import com.punjabibooktranslator.core.PromptBlock
import com.punjabibooktranslator.core.PromptBuilder
import com.punjabibooktranslator.core.PromptSpec
import com.punjabibooktranslator.core.ResponseParser
import com.punjabibooktranslator.core.SizeEstimator
import com.punjabibooktranslator.core.TranslationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {
    private val en = listOf(
        PromptBlock("P0011", "It was a cold, bright morning and the village was already awake."),
        PromptBlock("P0012", "\"Where are you going?\" she asked.\nHe did not answer."),
        PromptBlock("P0013", "The river, swollen by the night's rain, ran brown past the old mill."),
    )
    private val pa = listOf(
        PromptBlock("P0021", "ਇਹ ਇੱਕ ਠੰਢੀ ਸਵੇਰ ਸੀ ਅਤੇ ਪਿੰਡ ਦੇ ਲੋਕ ਖੇਤਾਂ ਵੱਲ ਜਾ ਰਹੇ ਸਨ।"),
        PromptBlock("P0022", "\u201Cਤੂੰ ਕਿੱਥੇ ਜਾ ਰਿਹਾ ਹੈਂ?\u201D ਉਸ ਨੇ ਪੁੱਛਿਆ।"),
    )
    private fun ids(b: List<PromptBlock>) = b.map { it.id }

    private fun spec(blocks: List<PromptBlock>, src: String, tgt: String, style: TranslationStyle = TranslationStyle.LITERARY) =
        PromptSpec("The Village", "A. Writer", "Chapter 2", "PDF pages 12-13", src, tgt, style, blocks,
            contextBefore = listOf(PromptBlock("P0010", "Previous paragraph.")),
            contextAfter = listOf(PromptBlock("P0014", "Next paragraph.")))

    // ---------- prompt generation ----------

    @Test fun englishToPunjabiPromptHasRealIdsTextAndGurmukhiRules() {
        val p = PromptBuilder.build(spec(en, "en", "pa"))
        for (b in en) { assertTrue(p.contains("\"id\":\"${b.id}\"")) }
        assertTrue(p.contains("It was a cold, bright morning"))
        assertTrue(p.contains("\\\"Where are you going?\\\" she asked.\\nHe did not answer."))  // escaped quote + newline
        assertTrue(p.contains("Gurmukhi script"))
        assertTrue(p.contains("Hindi-influenced"))
        assertTrue(p.contains("from English into Punjabi"))
        assertTrue(p.contains("The Village") && p.contains("A. Writer") && p.contains("Chapter 2"))
        assertTrue(p.contains("Include exactly 3 entries"))
        assertTrue(p.contains("Previous paragraph.") && p.contains("Next paragraph."))
        assertTrue(p.contains("<paragraph id exactly as given>"))      // schema uses a placeholder, not a sample id
        assertFalse(p.contains("\"id\":\"P0001\""))                    // no hardcoded demo ids
        assertFalse(p.contains("\"id\":\"P0010\""))                    // context is never in the translate list
    }

    @Test fun punjabiToEnglishPromptKeepsGurmukhiSourceIntact() {
        val p = PromptBuilder.build(spec(pa, "pa", "en", TranslationStyle.FAITHFUL))
        assertTrue(p.contains("ਇਹ ਇੱਕ ਠੰਢੀ ਸਵੇਰ ਸੀ ਅਤੇ ਪਿੰਡ ਦੇ ਲੋਕ ਖੇਤਾਂ ਵੱਲ ਜਾ ਰਹੇ ਸਨ।"))
        assertTrue(p.contains("from Punjabi (Gurmukhi script) into English"))
        assertTrue(p.contains("natural, fluent English"))
        assertTrue(p.contains("Faithful"))
        assertFalse(p.contains("hindi", ignoreCase = true) && p.contains("Hindi-influenced"))
    }

    @Test fun glossarySummaryAndStylesAppearWhenGiven() {
        val s = spec(en, "en", "pa", TranslationStyle.FORMAL).copy(
            chapterSummary = "The village wakes.", glossary = listOf("mill" to "ਚੱਕੀ"))
        val p = PromptBuilder.build(s)
        assertTrue(p.contains("GLOSSARY") && p.contains("mill => ਚੱਕੀ"))
        assertTrue(p.contains("CHAPTER SUMMARY") && p.contains("The village wakes."))
        assertTrue(p.contains("Formal"))
        for (st in TranslationStyle.entries) assertEquals(st, TranslationStyle.fromKey(st.name))
        assertEquals(TranslationStyle.LITERARY, TranslationStyle.fromKey("nonsense"))
    }

    @Test fun jsonQuoteEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", Json.quote("a\"b\\c\nd"))
        assertEquals("\"ਪੰਜਾਬੀ\"", Json.quote("ਪੰਜਾਬੀ"))
        assertEquals("\"\\u0001\"", Json.quote("\u0001"))
    }

    // ---------- parsing: the happy path and Gemini's usual wrappers ----------

    private fun json(vararg pairs: Pair<String, String>) =
        "{\"translations\":[" + pairs.joinToString(",") { "{\"id\":${Json.quote(it.first)},\"translated_text\":${Json.quote(it.second)}}" } + "]}"

    @Test fun cleanFencedJsonWithGurmukhi() {
        val body = json("P0011" to "ਇਹ ਇੱਕ ਠੰਢੀ, ਚਮਕਦਾਰ ਸਵੇਰ ਸੀ।", "P0012" to "\u201Cਤੂੰ ਕਿੱਥੇ ਜਾ ਰਿਹਾ ਹੈਂ?\u201D\nਉਸ ਨੇ ਕੋਈ ਜਵਾਬ ਨਾ ਦਿੱਤਾ।", "P0013" to "ਨਦੀ ਪੁਰਾਣੀ ਚੱਕੀ ਕੋਲੋਂ ਵਗ ਰਹੀ ਸੀ।")
        val r = ResponseParser.parse("Here you go!\n```json\n$body\n```\nHope this helps.", ids(en))
        assertNull(r.error)
        assertTrue(r.isClean)
        assertEquals(3, r.proposals.size)
        assertEquals("ਇਹ ਇੱਕ ਠੰਢੀ, ਚਮਕਦਾਰ ਸਵੇਰ ਸੀ।", r.proposals["P0011"])
        assertTrue(r.proposals["P0012"]!!.contains("\n"))
    }

    @Test fun englishOutputForPunjabiSource() {
        val r = ResponseParser.parse(json("P0021" to "It was a cold morning and the villagers were heading to the fields.",
            "P0022" to "\"Where are you going?\" he asked."), ids(pa))
        assertTrue(r.isClean)
        assertEquals("\"Where are you going?\" he asked.", r.proposals["P0022"])
    }

    @Test fun bareJsonWithSurroundingExplanation() {
        val r = ResponseParser.parse("Sure. " + json("P0011" to "a", "P0012" to "b", "P0013" to "c") + " Let me know!", ids(en))
        assertTrue(r.isClean)
    }

    @Test fun alternateKeysAndIdKeyedObjectsAndArrays() {
        val alt = "[{\"paragraph_id\":\"p0011\",\"translation\":\"x\"},{\"id\":\"P0012\",\"text\":\"y\"},{\"id\":\"P0013\",\"translated_text\":\"z\"}]"
        val r = ResponseParser.parse(alt, ids(en))
        assertTrue(r.isClean); assertEquals("x", r.proposals["P0011"])
        val keyed = ResponseParser.parse("{\"P0011\":\"a\",\"P0012\":\"b\",\"P0013\":\"c\"}", ids(en))
        assertTrue(keyed.isClean)
    }

    // ---------- parsing: every problem named in the spec ----------

    @Test fun missingIdsAreReportedAndNeverInvented() {
        val r = ResponseParser.parse(json("P0011" to "a", "P0013" to "c"), ids(en))
        assertEquals(listOf("P0012"), r.missingIds)
        assertFalse(r.proposals.containsKey("P0012"))
        assertFalse(r.isClean)
    }

    @Test fun emptyTranslationsCountAsMissing() {
        val r = ResponseParser.parse(json("P0011" to "a", "P0012" to "   ", "P0013" to "c"), ids(en))
        assertEquals(listOf("P0012"), r.emptyIds)
        assertEquals(listOf("P0012"), r.missingIds)
    }

    @Test fun duplicateIdsWithDifferentTextAreFlaggedIdenticalOnesAreNot() {
        val diff = ResponseParser.parse(json("P0011" to "a", "P0011" to "A2", "P0012" to "b", "P0013" to "c"), ids(en))
        assertEquals(listOf("P0011"), diff.duplicateIds)
        assertEquals("a", diff.proposals["P0011"])
        val same = ResponseParser.parse(json("P0011" to "a", "P0011" to "a", "P0012" to "b", "P0013" to "c"), ids(en))
        assertTrue(same.duplicateIds.isEmpty())
        assertTrue(same.warnings.any { it.contains("P0011") })
    }

    @Test fun unknownIdsAreKeptNotDiscarded() {
        val r = ResponseParser.parse(json("P0011" to "a", "P0012" to "b", "P0013" to "c", "P0099" to "stray"), ids(en))
        assertEquals(1, r.unknownEntries.size)
        assertEquals("stray", r.unknownEntries[0].text)
        assertFalse(r.isClean)
    }

    @Test fun truncatedResponseRecoversCompleteParagraphsOnly() {
        val full = json("P0011" to "first done", "P0012" to "second done", "P0013" to "third was being wri")
        val cut = full.substring(0, full.length - 12)   // chops inside the third value
        val r = ResponseParser.parse("```json\n$cut", ids(en))
        assertNull(r.error)
        assertTrue(r.truncated); assertTrue(r.recovered)
        assertEquals(listOf("P0011", "P0012"), r.proposals.keys.toList())
        assertEquals(listOf("P0013"), r.missingIds)
        assertFalse(r.proposals.values.any { it.contains("wri") })       // the half-written paragraph is not used
    }

    @Test fun truncatedBeforeAnyCompleteParagraphIsAnErrorNotInvention() {
        val r = ResponseParser.parse("{\"translations\":[{\"id\":\"P0011\",\"translated_text\":\"ਇਹ ਇੱ", ids(en))
        assertNotNull(r.error)
        assertTrue(r.truncated)
        assertTrue(r.proposals.isEmpty())
        assertEquals(ids(en), r.missingIds)
    }

    @Test fun malformedJsonWithBadQuoteKeepsTheGoodEntries() {
        val bad = "{\"translations\":[{\"id\":\"P0011\",\"translated_text\":\"fine\"}," +
            "{\"id\":\"P0012\",\"translated_text\":\"He said \"hello\" badly\"}," +
            "{\"id\":\"P0013\",\"translated_text\":\"also fine\"}]}"
        val r = ResponseParser.parse(bad, ids(en))
        assertTrue(r.recovered); assertFalse(r.truncated)
        assertEquals("fine", r.proposals["P0011"]); assertEquals("also fine", r.proposals["P0013"])
        assertEquals(listOf("P0012"), r.missingIds)
    }

    @Test fun plainTextLabelsAreAFlaggedLastResort() {
        val txt = "**P0011:** ਇਹ ਪਹਿਲਾ ਹੈ।\n\n[P0012] ਦੂਜਾ ਹੈ।\nਦੂਜੇ ਦੀ ਦੂਜੀ ਸਤਰ।\nP0013 - ਤੀਜਾ।"
        val r = ResponseParser.parse(txt, ids(en))
        assertNull(r.error); assertTrue(r.recovered)
        assertEquals("ਇਹ ਪਹਿਲਾ ਹੈ।", r.proposals["P0011"])
        assertEquals("ਦੂਜਾ ਹੈ।\nਦੂਜੇ ਦੀ ਦੂਜੀ ਸਤਰ।", r.proposals["P0012"])
        assertEquals("ਤੀਜਾ।", r.proposals["P0013"])
    }

    @Test fun garbageAndEmptyInputGiveErrors() {
        assertNotNull(ResponseParser.parse("", ids(en)).error)
        assertNotNull(ResponseParser.parse("I'm sorry, I can't help with that.", ids(en)).error)
    }

    @Test fun valueEscapesAndUnicodeEscapesDecode() {
        val r = ResponseParser.parse("{\"translations\":[{\"id\":\"P0011\",\"translated_text\":\"a\\u0A2A\\n\\\"q\\\"\"}]}", listOf("P0011"))
        assertEquals("a\u0A2A\n\"q\"", r.proposals["P0011"])
    }

    // ---------- repair prompt and sizing ----------

    @Test fun repairPromptCarriesResponseAndIdsAndForbidsChanges() {
        val p = PromptBuilder.buildRepair(ids(en), "garbled response text ਪੰਜਾਬੀ", "it was not valid JSON")
        assertTrue(p.contains("garbled response text ਪੰਜਾਬੀ"))
        assertTrue(p.contains("P0011, P0012, P0013"))
        assertTrue(p.contains("WITHOUT changing"))
        assertTrue(p.contains("do not invent"))
    }

    @Test fun splitNeverDropsOrReordersParagraphs() {
        val many = (1..200).map { PromptBlock("P%04d".format(it), "word ".repeat(120).trim() + ".") }
        val parts = SizeEstimator.split(many, "pa")
        assertTrue(parts.size > 1)
        assertEquals(many.map { it.id }, parts.flatten().map { it.id })
        for (part in parts) {
            val t = part.sumOf { SizeEstimator.blockOutputTokens(it, "pa") }
            assertTrue(part.size == 1 || t <= SizeEstimator.MAX_OUTPUT_TOKENS_PER_REQUEST)
        }
        val huge = listOf(PromptBlock("P0001", "x".repeat(50_000)), PromptBlock("P0002", "short"))
        val hp = SizeEstimator.split(huge, "pa")
        assertEquals(2, hp.size)                         // oversized paragraph isolated, nothing truncated
        assertEquals(50_000, hp[0][0].text.length)
        assertEquals(1, SizeEstimator.split(en, "pa").size)
    }
}
