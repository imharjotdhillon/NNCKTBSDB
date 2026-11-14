package com.punjabibooktranslator.core

enum class GlossaryType(val label: String) {
    CHARACTER("Character"),
    PLACE("Place"),
    CULTURAL("Cultural term"),
    PHRASE("Phrase"),
    GENERAL("General vocabulary");

    companion object {
        fun fromKey(key: String?): GlossaryType = entries.firstOrNull { it.name == key } ?: GENERAL
    }
}

data class GlossaryTerm(
    val id: Long,
    val term: String,
    val translation: String,
    val type: GlossaryType,
    val explanation: String?,
    val approved: Boolean,
)

/** Finds a term inside text. Latin terms match whole words ignoring case; other scripts (Gurmukhi) match as substrings. */
object TermMatcher {
    fun key(term: String): String = TextUtils.normalize(term).lowercase().replace(Regex("\\s+"), " ").trim()

    private fun hasLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    private fun flat(s: String) = TextUtils.normalize(s).replace(Regex("\\s+"), " ")

    fun count(text: String, term: String): Int {
        val t = flat(term).trim()
        if (t.isEmpty()) return 0
        val hay = flat(text)
        val latin = hasLatin(t)
        var n = 0
        var from = 0
        while (from <= hay.length - t.length) {
            val i = if (latin) SearchText.indexOfIgnoreCase(hay, t, from) else hay.indexOf(t, from)
            if (i < 0) break
            val before = if (i > 0) hay[i - 1] else ' '
            val after = if (i + t.length < hay.length) hay[i + t.length] else ' '
            val bounded = !latin || (!before.isLetterOrDigit() && !after.isLetterOrDigit())
            if (bounded) n++
            from = i + t.length
        }
        return n
    }

    fun contains(text: String, term: String) = count(text, term) > 0
}

data class RelevantTerms(val terms: List<GlossaryTerm>, val omitted: Int)

object GlossaryMatcher {
    /**
     * Only the entries that actually occur in [texts] are returned, so a prompt never carries the whole glossary.
     * Draft (not user-approved) entries and entries without a translation are never sent.
     */
    fun relevant(all: List<GlossaryTerm>, texts: List<String>, max: Int = 40): RelevantTerms {
        val hay = texts.joinToString("\n")
        val hits = all.filter { it.approved && it.term.isNotBlank() && it.translation.isNotBlank() && TermMatcher.contains(hay, it.term) }
        val sorted = hits.sortedWith(
            compareByDescending<GlossaryTerm> { it.term.length }.thenBy { TermMatcher.key(it.term) }
        )
        return RelevantTerms(sorted.take(max), (sorted.size - max).coerceAtLeast(0))
    }

    /** Lines for [PromptSpec.glossary]: "Harpreet [Character: Amrit's brother]" => translation. */
    fun promptLines(terms: List<GlossaryTerm>): List<Pair<String, String>> = terms.map { t ->
        val note = t.explanation?.trim()?.replace('\n', ' ')?.takeIf { it.isNotEmpty() }?.let { if (it.length > 120) it.take(120) + "…" else it }
        val tag = when {
            t.type != GlossaryType.GENERAL && note != null -> " [${t.type.label}: $note]"
            t.type != GlossaryType.GENERAL -> " [${t.type.label}]"
            note != null -> " [$note]"
            else -> ""
        }
        (t.term.trim() + tag) to t.translation.trim()
    }
}

enum class OccState { HAS_APPROVED, HAS_OLD, MISSING, UNTRANSLATED }

data class OccRow(val blockId: Long, val paraId: String, val page: Int?, val original: String, val translation: String?)
data class OccResult(val row: OccRow, val state: OccState)

/** Checks where a recurring term appears and whether each saved translation uses the approved rendering. */
object TermChecker {
    fun check(rows: List<OccRow>, term: String, approved: String, old: String? = null): List<OccResult> =
        rows.filter { TermMatcher.contains(it.original, term) }.map { r ->
            val tr = r.translation
            val state = when {
                tr == null -> OccState.UNTRANSLATED
                approved.isNotBlank() && TermMatcher.contains(tr, approved) -> OccState.HAS_APPROVED
                !old.isNullOrBlank() && TermMatcher.contains(tr, old) -> OccState.HAS_OLD
                else -> OccState.MISSING
            }
            OccResult(r, state)
        }

    fun needsReview(s: OccState) = s == OccState.HAS_OLD || s == OccState.MISSING

    fun label(s: OccState) = when (s) {
        OccState.HAS_APPROVED -> "Uses the approved rendering"
        OccState.HAS_OLD -> "Still uses the old rendering"
        OccState.MISSING -> "Approved rendering not found"
        OccState.UNTRANSLATED -> "Not translated yet"
    }
}

data class TermCandidate(val term: String, val count: Int)

/**
 * Suggests recurring proper names from Latin-script text so the user can fix one spelling for them. It cannot work for
 * Gurmukhi (no capital letters), where the user adds terms by hand. These are suggestions, never applied automatically.
 */
object TermCandidates {
    private val stop = setOf(
        "I", "The", "A", "An", "He", "She", "It", "We", "They", "You", "His", "Her", "Their", "Our", "My", "Your", "This", "That",
        "These", "Those", "There", "Then", "When", "Where", "What", "Who", "Why", "How", "But", "And", "Or", "So", "If", "In", "On",
        "At", "As", "Of", "To", "For", "From", "With", "By", "After", "Before", "While", "Mr", "Mrs", "Ms", "Dr", "Sir", "Chapter",
        "Yes", "No", "Oh", "Well", "Now", "Not", "Is", "Was", "Do", "Did", "Does", "Are", "Were", "Be", "Have", "Had", "Has", "Will",
        "Would", "Can", "Could", "Should", "May", "Might", "Must", "One", "Two", "Three", "Each", "Every", "Some", "Any", "All",
        "Both", "Many", "Most", "Other", "Another", "Such", "Only", "Just", "Even", "Still", "Also", "Too", "Very", "Here", "Perhaps",
        "Maybe", "Once", "Today", "Tomorrow", "Yesterday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "June", "July", "August", "September", "October", "November", "December",
        "Let", "Come", "Go", "Look", "See", "Please", "Thank", "Thanks", "Hello", "Good", "Bad", "Great", "Little", "Old", "New",
    )
    private val abbreviations = setOf("mr", "mrs", "ms", "dr", "st", "sr", "jr", "prof", "mt")
    private val tokenRe = Regex("\\p{L}[\\p{L}'’-]*")

    private class Tok(val text: String, val start: Int, val end: Int, val cap: Boolean, val initial: Boolean)

    private fun tokens(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        for (m in tokenRe.findAll(text)) {
            var w = m.value.trimEnd('\'', '’', '-')
            if (w.endsWith("'s") || w.endsWith("’s")) w = w.dropLast(2)
            if (w.isEmpty()) continue
            val cap = w[0].isUpperCase()
            out.add(Tok(w, m.range.first, m.range.first + w.length, cap, initialAt(text, m.range.first)))
        }
        return out
    }

    private fun initialAt(text: String, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && (text[i].isWhitespace() || text[i] in "\"'“‘([—-")) i--
        if (i < 0) return true
        if (text[i] == '.') {
            var j = i - 1
            while (j >= 0 && text[j].isLetter()) j--
            val prev = text.substring(j + 1, i).lowercase()
            return prev !in abbreviations
        }
        return text[i] == '!' || text[i] == '?'
    }

    fun find(texts: List<String>, existingKeys: Set<String> = emptySet(), minCount: Int = 3, max: Int = 60): List<TermCandidate> {
        val capTotal = HashMap<String, Int>()
        val midCap = HashMap<String, Int>()
        val lowerSeen = HashSet<String>()
        val phrases = HashMap<String, Int>()
        for (raw in texts) {
            val text = TextUtils.normalize(raw)
            val toks = tokens(text)
            for (t in toks) {
                if (t.cap) {
                    capTotal.merge(t.text, 1, Int::plus)
                    if (!t.initial) midCap.merge(t.text, 1, Int::plus)
                } else lowerSeen.add(t.text.lowercase())
            }
            var i = 0
            while (i < toks.size) {
                if (!toks[i].cap) { i++; continue }
                var j = i
                while (j + 1 < toks.size && toks[j + 1].cap && text.substring(toks[j].end, toks[j + 1].start) == " ") j++
                var a = i
                while (a <= j && toks[a].text in stop) a++
                val len = j - a + 1
                if (len in 2..3) {
                    phrases.merge(toks.subList(a, j + 1).joinToString(" ") { it.text }, 1, Int::plus)
                }
                i = j + 1
            }
        }
        val out = ArrayList<TermCandidate>()
        for ((w, n) in capTotal) {
            if (w in stop || w.length < 2 || n < minCount) continue
            if ((midCap[w] ?: 0) < 1 || w.lowercase() in lowerSeen) continue
            if (TermMatcher.key(w) in existingKeys) continue
            out.add(TermCandidate(w, n))
        }
        for ((p, n) in phrases) {
            if (n < minCount || TermMatcher.key(p) in existingKeys) continue
            out.add(TermCandidate(p, n))
        }
        return out.sortedWith(compareByDescending<TermCandidate> { it.count }.thenBy { it.term }).take(max)
    }
}

/** Keeps the "previously approved translations" part of a prompt small and relevant. */
object ExampleLimiter {
    fun pick(examples: List<Pair<String, String>>, maxCount: Int = 3, maxChars: Int = 500): List<Pair<String, String>> =
        examples.filter { it.first.isNotBlank() && it.second.isNotBlank() && it.first.length <= maxChars && it.second.length <= maxChars }
            .distinctBy { TermMatcher.key(it.first) }
            .take(maxCount)
}
