package com.punjabibooktranslator.core

import kotlin.math.ceil

/** Translation styles from the spec. The enum name is what gets stored in the database. */
enum class TranslationStyle(val label: String, val blurb: String, val instruction: String) {
    LITERARY(
        "Literary", "Natural, expressive, faithful to the author's voice (default)",
        "Literary: natural and expressive, faithful to the author's voice, rhythm and imagery. Prefer the phrasing a gifted " +
            "native novelist would choose over word-for-word rendering, without changing the meaning.",
    ),
    NATURAL(
        "Natural", "Fluent everyday language",
        "Natural: fluent, everyday language that reads as if it had originally been written in the target language.",
    ),
    FAITHFUL(
        "Faithful", "Follows the original structure closely",
        "Faithful: stay close to the original sentence structure and word choice while remaining grammatical and readable.",
    ),
    FORMAL(
        "Formal", "More formal vocabulary and register",
        "Formal: use a more formal vocabulary and register, suitable for official or scholarly reading.",
    );

    companion object {
        fun fromKey(key: String?): TranslationStyle = entries.firstOrNull { it.name == key } ?: LITERARY
    }
}

data class PromptBlock(val id: String, val text: String)

data class PromptSpec(
    val bookTitle: String,
    val author: String?,
    val chapterTitle: String?,
    /** e.g. "PDF pages 12-16" or "paragraphs P0101-P0140". */
    val locationLabel: String?,
    val sourceLang: String,
    val targetLang: String,
    val style: TranslationStyle,
    val blocks: List<PromptBlock>,
    val contextBefore: List<PromptBlock> = emptyList(),
    val contextAfter: List<PromptBlock> = emptyList(),
    val chapterSummary: String? = null,
    /** Source term -> approved target term. Populated from Phase 4 onward. */
    val glossary: List<Pair<String, String>> = emptyList(),
    /** Previously approved (source, translation) pairs, only to keep names and terms consistent. */
    val approvedExamples: List<Pair<String, String>> = emptyList(),
)

object Json {
    /** Quotes and escapes [s] as a JSON string literal. Non-ASCII text (Gurmukhi) is kept as-is. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

object PromptBuilder {
    private fun langName(code: String) = if (code == "pa") "Punjabi (Gurmukhi script)" else "English"

    private fun directionRules(target: String): List<String> =
        if (target == "pa") listOf(
            "Write natural, fluent Punjabi in Gurmukhi script (Unicode). Never use Latin transliteration or Shahmukhi.",
            "Use correct Punjabi grammar, spelling, punctuation and idiom.",
            "Avoid unnecessarily Hindi-influenced vocabulary when a natural Punjabi equivalent exists.",
            "Preserve the author's meaning, tone, emotion, voice and literary style. Do not simplify literary writing unnecessarily.",
            "Render every proper name the same way each time it appears; do not translate names inconsistently.",
        ) else listOf(
            "Write natural, fluent English.",
            "Preserve meaning, idioms, cultural expressions, tone and emotional nuance. Do not flatten culturally meaningful expressions into bland equivalents.",
            "Do not omit or invent information.",
            "Render every proper name the same way each time it appears, using one consistent Latin-script spelling.",
        )

    private const val SCHEMA =
        "{\"translations\":[{\"id\":\"<paragraph id exactly as given>\",\"translated_text\":\"<translation of that paragraph>\"}]}"

    fun build(spec: PromptSpec): String {
        require(spec.blocks.isNotEmpty()) { "Cannot build a prompt without paragraphs." }
        val src = langName(spec.sourceLang)
        val tgt = langName(spec.targetLang)
        val sb = StringBuilder()
        sb.append("You are a professional literary translator working from $src into $tgt. ")
        sb.append("You are translating part of a book. You are a translator, not a summarizer.\n\n")

        sb.append("BOOK: ").append(spec.bookTitle)
        spec.author?.takeIf { it.isNotBlank() }?.let { sb.append(" — by ").append(it) }
        sb.append('\n')
        val section = listOfNotNull(spec.chapterTitle?.takeIf { it.isNotBlank() }, spec.locationLabel?.takeIf { it.isNotBlank() })
        if (section.isNotEmpty()) sb.append("SECTION: ").append(section.joinToString(" — ")).append('\n')
        sb.append("TRANSLATION STYLE: ").append(spec.style.instruction).append("\n\n")

        sb.append("RULES\n")
        val rules = mutableListOf(
            "Translate EVERY paragraph below completely. Do not omit, summarize, shorten, invent, merge, split or reorder anything.",
            "Do not add explanations, notes or commentary to the translated text.",
        ) + directionRules(spec.targetLang) + listOf(
            "Paragraphs listed under CONTEXT are for understanding only. Do NOT translate them and do NOT include them in your output.",
            "Reply with the JSON described under OUTPUT FORMAT and nothing else.",
        )
        rules.forEachIndexed { i, r -> sb.append(i + 1).append(". ").append(r).append('\n') }
        sb.append('\n')

        if (spec.glossary.isNotEmpty()) {
            sb.append("GLOSSARY (use these approved renderings):\n")
            spec.glossary.forEach { (a, b) -> sb.append("- ").append(a).append(" => ").append(b).append('\n') }
            sb.append('\n')
        }
        if (spec.approvedExamples.isNotEmpty()) {
            sb.append("PREVIOUSLY APPROVED TRANSLATIONS (only to keep names and terms consistent; never copy sentences from them):\n")
            spec.approvedExamples.forEach { (a, b) ->
                sb.append("- ").append(a.replace('\n', ' ')).append("  =>  ").append(b.replace('\n', ' ')).append('\n')
            }
            sb.append('\n')
        }
        spec.chapterSummary?.takeIf { it.isNotBlank() }?.let {
            sb.append("CHAPTER SUMMARY (background only):\n").append(it.trim()).append("\n\n")
        }
        if (spec.contextBefore.isNotEmpty()) {
            sb.append("CONTEXT — PRECEDING PARAGRAPHS (do not translate):\n")
            spec.contextBefore.forEach { sb.append("- ").append(it.text.replace('\n', ' ')).append('\n') }
            sb.append('\n')
        }

        sb.append("PARAGRAPHS TO TRANSLATE (${spec.blocks.size}):\n")
        sb.append("{\"paragraphs\":[\n")
        spec.blocks.forEachIndexed { i, b ->
            sb.append("  {\"id\":").append(Json.quote(b.id)).append(",\"text\":").append(Json.quote(b.text)).append('}')
            sb.append(if (i < spec.blocks.lastIndex) ",\n" else "\n")
        }
        sb.append("]}\n\n")

        if (spec.contextAfter.isNotEmpty()) {
            sb.append("CONTEXT — FOLLOWING PARAGRAPHS (do not translate):\n")
            spec.contextAfter.forEach { sb.append("- ").append(it.text.replace('\n', ' ')).append('\n') }
            sb.append('\n')
        }

        sb.append("OUTPUT FORMAT\n")
        sb.append("Return one JSON object inside a single ```json code block, using exactly this structure:\n")
        sb.append(SCHEMA).append('\n')
        sb.append("Include exactly ${spec.blocks.size} entries, one for each paragraph id above, in the same order, ")
        sb.append("copying each id exactly. Inside the JSON, escape double quotes as \\\" and line breaks as \\n so it stays valid.\n")
        return sb.toString()
    }

    /** Asks the AI to reformat an unusable response WITHOUT changing the translation. */
    fun buildRepair(expectedIds: List<String>, rawResponse: String, problem: String): String {
        val sb = StringBuilder()
        sb.append("Below is a response to a translation request. I could not use it because: $problem\n\n")
        sb.append("Reformat it WITHOUT changing, improving, shortening or adding to any translated text. ")
        sb.append("Do not translate anything yourself and do not invent text for a paragraph that has none; ")
        sb.append("if a paragraph id from the list has no translation below, simply leave its entry out.\n\n")
        sb.append("Return one JSON object inside a single ```json code block, using exactly this structure:\n")
        sb.append(SCHEMA).append("\n")
        sb.append("Escape double quotes inside text as \\\" and line breaks as \\n so the JSON is valid.\n\n")
        sb.append("The paragraph ids that should be present, in order: ").append(expectedIds.joinToString(", ")).append("\n\n")
        sb.append("RESPONSE TO REFORMAT (between the markers):\n<<<BEGIN RESPONSE\n")
        sb.append(rawResponse.trim()).append("\nEND RESPONSE>>>\n")
        return sb.toString()
    }
}

/** Deliberately conservative size estimates; real the AI limits are not known to the app. */
object SizeEstimator {
    const val MAX_OUTPUT_TOKENS_PER_REQUEST = 6000
    const val MAX_PROMPT_TOKENS = 30000

    fun tokens(chars: Int, lang: String): Int = ceil(chars / (if (lang == "pa") 1.4 else 3.5)).toInt()

    fun outputTokensForChars(chars: Int, targetLang: String): Int =
        tokens((chars * 1.2).toInt(), targetLang) + 14 // JSON wrapper + id

    fun blockOutputTokens(block: PromptBlock, targetLang: String): Int = outputTokensForChars(block.text.length, targetLang)

    fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    /**
     * Greedily groups items into requests whose estimated output stays under [maxOutputTokens].
     * Never splits, drops or reorders an item; a single oversized item becomes its own request.
     */
    fun <T> splitBy(
        items: List<T>,
        charsOf: (T) -> Int,
        targetLang: String,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS_PER_REQUEST,
    ): List<List<T>> {
        val parts = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        var used = 0
        for (item in items) {
            val t = outputTokensForChars(charsOf(item), targetLang)
            if (cur.isNotEmpty() && used + t > maxOutputTokens) {
                parts.add(cur); cur = ArrayList(); used = 0
            }
            cur.add(item); used += t
        }
        if (cur.isNotEmpty()) parts.add(cur)
        return parts
    }

    fun split(
        blocks: List<PromptBlock>,
        targetLang: String,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS_PER_REQUEST,
    ): List<List<PromptBlock>> = splitBy(blocks, { it.text.length }, targetLang, maxOutputTokens)
}

data class ParsedEntry(val id: String, val text: String)

data class ParseResult(
    /** Expected paragraph id -> proposed translation (non-blank, first occurrence). */
    val proposals: Map<String, String>,
    /** Expected ids with no usable translation (includes [emptyIds]). */
    val missingIds: List<String>,
    val emptyIds: List<String>,
    /** Ids that appeared more than once with DIFFERENT text; the first is proposed, the user must check. */
    val duplicateIds: List<String>,
    /** Entries whose id was not requested. Kept so nothing is silently discarded. */
    val unknownEntries: List<ParsedEntry>,
    val truncated: Boolean,
    /** True when the response was not clean JSON and entries were salvaged or read from plain text. */
    val recovered: Boolean,
    val warnings: List<String>,
    /** Non-null only when nothing usable could be read. */
    val error: String?,
) {
    val isClean: Boolean
        get() = error == null && missingIds.isEmpty() && duplicateIds.isEmpty() && unknownEntries.isEmpty() && !truncated && !recovered
}

internal sealed class J {
    data class Str(val v: String) : J()
    data class Num(val raw: String) : J()
    data class Bool(val v: Boolean) : J()
    object Null : J()
    data class Arr(val items: List<J>) : J()
    data class Obj(val map: LinkedHashMap<String, J>) : J()
}

internal class JsonParseException(message: String, val atEnd: Boolean) : Exception(message)

/** Small, lenient JSON reader (allows raw newlines in strings and trailing commas). */
internal class JsonReader(private val s: String) {
    var pos = 0

    private fun fail(msg: String): Nothing = throw JsonParseException(msg, pos >= s.length)

    private fun skipWs() {
        while (pos < s.length && (s[pos].isWhitespace() || s[pos] == '\uFEFF')) pos++
    }

    fun parseTop(): J = value()

    private fun value(): J {
        skipWs()
        if (pos >= s.length) fail("The response ended unexpectedly")
        return when (val c = s[pos]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> J.Str(str())
            't', 'f', 'n' -> literal()
            else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected character '$c'")
        }
    }

    private fun literal(): J {
        for ((word, v) in listOf("true" to J.Bool(true), "false" to J.Bool(false), "null" to J.Null)) {
            if (s.startsWith(word, pos)) { pos += word.length; return v }
        }
        if (s.length - pos < 5) { pos = s.length; fail("The response ended unexpectedly") }
        fail("Unexpected text")
    }

    private fun num(): J {
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
        return J.Num(s.substring(start, pos))
    }

    private fun str(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) fail("A text value was cut off")
            val c = s[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (pos >= s.length) fail("A text value was cut off")
                when (val e = s[pos++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (pos + 4 > s.length) { pos = s.length; fail("A text value was cut off") }
                        val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("Invalid \\u escape")
                        sb.append(code.toChar()); pos += 4
                    }
                    else -> sb.append(e) // \" \\ \/ and lenient unknown escapes
                }
            } else sb.append(c)
        }
    }

    private fun arr(): J {
        pos++
        val items = ArrayList<J>()
        while (true) {
            skipWs()
            if (pos >= s.length) fail("The response ended inside a list")
            if (s[pos] == ']') { pos++; break }
            items.add(value())
            skipWs()
            if (pos >= s.length) fail("The response ended inside a list")
            when (s[pos]) {
                ',' -> pos++
                ']' -> { pos++; break }
                else -> fail("Expected ',' or ']'")
            }
        }
        return J.Arr(items)
    }

    private fun obj(): J {
        pos++
        val map = LinkedHashMap<String, J>()
        while (true) {
            skipWs()
            if (pos >= s.length) fail("The response ended inside an object")
            if (s[pos] == '}') { pos++; break }
            if (s[pos] != '"') fail("Expected a quoted name")
            val k = str()
            skipWs()
            if (pos >= s.length) fail("The response ended inside an object")
            if (s[pos] != ':') fail("Expected ':'")
            pos++
            map[k] = value()
            skipWs()
            if (pos >= s.length) fail("The response ended inside an object")
            when (s[pos]) {
                ',' -> pos++
                '}' -> { pos++; break }
                else -> fail("Expected ',' or '}'")
            }
        }
        return J.Obj(map)
    }
}

object ResponseParser {
    private val idKeys = listOf("id", "paragraph_id", "para_id", "paragraphId")
    private val textKeys = listOf("translated_text", "translation", "translatedText", "text", "target")

    private fun normId(id: String) = id.trim().uppercase()

    private fun field(o: J.Obj, keys: List<String>): J? =
        keys.firstNotNullOfOrNull { k -> o.map.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value }

    private fun asText(j: J?): String? = when (j) {
        is J.Str -> j.v
        is J.Num -> j.raw
        else -> null
    }

    private fun looksLikeEntry(o: J.Obj) = asText(field(o, idKeys)) != null && field(o, textKeys) is J.Str

    private fun entryOf(o: J.Obj): ParsedEntry? {
        val id = asText(field(o, idKeys)) ?: return null
        val text = (field(o, textKeys) as? J.Str)?.v ?: return null
        return ParsedEntry(normId(id), text)
    }

    private fun fromArray(a: J.Arr, warnings: MutableList<String>): List<ParsedEntry> {
        val out = ArrayList<ParsedEntry>()
        a.items.forEachIndexed { i, item ->
            val e = (item as? J.Obj)?.let(::entryOf)
            if (e != null) out.add(e) else warnings.add("List item ${i + 1} has no usable id/translation and was skipped.")
        }
        return out
    }

    private fun extractEntries(v: J, warnings: MutableList<String>): List<ParsedEntry>? = when (v) {
        is J.Arr -> fromArray(v, warnings)
        is J.Obj -> {
            val key = v.map.keys.firstOrNull { it.equals("translations", ignoreCase = true) }
            val arr = (if (key != null) v.map[key] else v.map.values.firstOrNull { it is J.Arr }) as? J.Arr
            when {
                arr != null -> fromArray(arr, warnings)
                looksLikeEntry(v) -> listOfNotNull(entryOf(v))
                v.map.isNotEmpty() && v.map.all { (k, x) -> Regex("(?i)^P\\d+$").matches(k.trim()) && x is J.Str } ->
                    v.map.map { (k, x) -> ParsedEntry(normId(k), (x as J.Str).v) }
                else -> null
            }
        }
        else -> null
    }

    private fun extractCandidate(text: String): Fences.Block = Fences.pickJson(text, listOf("translated_text", "translations"))

    /** Pulls out every complete entry object it can find, even from broken or cut-off JSON. */
    private fun salvage(s: String): Pair<List<ParsedEntry>, Boolean> {
        val out = ArrayList<ParsedEntry>()
        var sawEnd = false
        var i = 0
        while (i < s.length) {
            val j = s.indexOf('{', i)
            if (j < 0) break
            val r = JsonReader(s)
            r.pos = j
            try {
                val v = r.parseTop()
                if (v is J.Obj && looksLikeEntry(v)) {
                    entryOf(v)?.let { out.add(it) }
                    i = r.pos
                } else i = j + 1
            } catch (e: JsonParseException) {
                if (e.atEnd) sawEnd = true
                i = j + 1
            }
        }
        return out to sawEnd
    }

    private val labelRegex = Regex("(?i)^[\\s>*#_\\-\\[(]*(P\\d{3,6})[\\]*_)]*\\s*[:.\\u2013\\u2014-]?\\s*(.*)$")

    /** Last resort: "P0001: text" style output with no JSON. */
    private fun plainText(s: String): List<ParsedEntry> {
        val out = ArrayList<ParsedEntry>()
        var curId: String? = null
        val buf = StringBuilder()
        fun flush() { curId?.let { out.add(ParsedEntry(normId(it), buf.toString().trim())) }; buf.setLength(0) }
        for (line in s.lines()) {
            val m = labelRegex.matchEntire(line)
            if (m != null) {
                flush(); curId = m.groupValues[1]; buf.append(m.groupValues[2].trim('*', ' '))
            } else if (curId != null) buf.append('\n').append(line)
        }
        flush()
        return out
    }

    fun parse(raw: String, expectedIds: List<String>): ParseResult {
        val text = raw.trim()
        if (text.isEmpty()) return failure("The pasted response is empty.")
        val warnings = ArrayList<String>()
        val cand = extractCandidate(text)
        var entries: List<ParsedEntry>? = null
        var truncated = false
        var recovered = false

        var strictMessage: String? = null
        var strictAtEnd = false
        try {
            val v = JsonReader(cand.text).parseTop()
            entries = extractEntries(v, warnings)
            if (entries == null) strictMessage = "The JSON was valid but did not contain a list of translations."
        } catch (e: JsonParseException) {
            strictMessage = e.message
            strictAtEnd = e.atEnd
        }

        if (entries == null) {
            val (salvaged, sawEnd) = salvage(cand.text)
            if (salvaged.isNotEmpty()) {
                entries = salvaged
                recovered = true
                truncated = strictAtEnd || sawEnd || cand.unclosed
                warnings.add(
                    if (truncated) "The response looks cut off. ${salvaged.size} complete paragraph(s) were recovered; the rest are missing."
                    else "The response was not valid JSON. ${salvaged.size} paragraph(s) were recovered; check the rest."
                )
            } else {
                val plain = plainText(text)
                if (plain.isNotEmpty()) {
                    entries = plain
                    recovered = true
                    warnings.add("The response was not JSON. Paragraphs were read from plain-text id labels; please review each one.")
                } else {
                    return failure(
                        "Couldn't read this response${strictMessage?.let { " ($it)" } ?: ""}. " +
                            if (strictAtEnd || cand.unclosed) "It looks cut off before any complete paragraph." else "No paragraph ids were found.",
                        truncated = strictAtEnd || cand.unclosed,
                        expected = expectedIds,
                    )
                }
            }
        }
        return validate(entries, expectedIds, warnings, truncated, recovered)
    }

    private fun failure(message: String, truncated: Boolean = false, expected: List<String> = emptyList()) = ParseResult(
        proposals = emptyMap(), missingIds = expected, emptyIds = emptyList(), duplicateIds = emptyList(),
        unknownEntries = emptyList(), truncated = truncated, recovered = false, warnings = emptyList(), error = message,
    )

    private fun validate(
        entries: List<ParsedEntry>,
        expectedIds: List<String>,
        warnings: MutableList<String>,
        truncated: Boolean,
        recovered: Boolean,
    ): ParseResult {
        val expected = expectedIds.toSet()
        val byId = LinkedHashMap<String, MutableList<String>>()
        val unknown = ArrayList<ParsedEntry>()
        for (e in entries) {
            if (e.id in expected) byId.getOrPut(e.id) { ArrayList() }.add(e.text) else unknown.add(e)
        }
        val proposals = LinkedHashMap<String, String>()
        val missing = ArrayList<String>()
        val empty = ArrayList<String>()
        val duplicates = ArrayList<String>()
        for (id in expectedIds) {
            val texts = byId[id]
            if (texts == null) { missing.add(id); continue }
            val nonBlank = texts.map { it.trim() }.filter { it.isNotEmpty() }
            if (nonBlank.isEmpty()) { empty.add(id); missing.add(id); continue }
            proposals[id] = nonBlank.first()
            if (nonBlank.size > 1) {
                if (nonBlank.distinct().size > 1) duplicates.add(id)
                else warnings.add("$id appeared more than once with identical text.")
            }
        }
        if (entries.isEmpty()) warnings.add("The response contained no translation entries.")
        return ParseResult(proposals, missing, empty, duplicates, unknown, truncated, recovered, warnings, null)
    }
}
