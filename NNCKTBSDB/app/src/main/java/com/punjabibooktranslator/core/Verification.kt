package com.punjabibooktranslator.core

enum class Severity(val label: String, val rank: Int) {
    MINOR("Minor", 1), MODERATE("Moderate", 2), MAJOR("Major", 3);

    companion object {
        fun parse(s: String?): Severity? = when (s?.trim()?.lowercase()) {
            "minor", "low", "small", "trivial" -> MINOR
            "moderate", "medium", "mid" -> MODERATE
            "major", "high", "critical", "severe", "serious" -> MAJOR
            else -> null
        }
    }
}

/** Per-paragraph verification state, stored on the translation. Absence of a report never means "verified". */
object VerifyStates {
    const val NOT_VERIFIED = "NOT_VERIFIED"
    const val VERIFIED = "VERIFIED"
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val MINOR_ISSUES = "MINOR_ISSUES"
    const val MAJOR_ISSUES = "MAJOR_ISSUES"

    fun forSeverity(s: Severity) = when (s) {
        Severity.MINOR -> MINOR_ISSUES
        Severity.MODERATE -> NEEDS_REVIEW
        Severity.MAJOR -> MAJOR_ISSUES
    }

    fun label(state: String) = when (state) {
        VERIFIED -> "Verified"
        NEEDS_REVIEW -> "Needs review"
        MINOR_ISSUES -> "Minor issues"
        MAJOR_ISSUES -> "Major issues"
        else -> "Not verified"
    }

    fun isUnresolved(state: String) = state == NEEDS_REVIEW || state == MINOR_ISSUES || state == MAJOR_ISSUES

    /** State implied by the still-open findings of a paragraph: none left means the user has cleared it. */
    fun fromOpenSeverities(severities: List<String>): String {
        val worst = severities.mapNotNull { Severity.parse(it) }.maxByOrNull { it.rank } ?: return VERIFIED
        return forSeverity(worst)
    }

    /** Flagging for review never lowers a more serious state. */
    fun flagged(current: String) = if (current == MAJOR_ISSUES) MAJOR_ISSUES else NEEDS_REVIEW
}

object FindingStates {
    const val OPEN = "OPEN"
    const val ACCEPTED = "ACCEPTED"
    const val EDITED = "EDITED"
    const val REJECTED = "REJECTED"
    /** The translation text changed after the finding was made, so the finding no longer describes it. */
    const val SUPERSEDED = "SUPERSEDED"
}

data class VerifyItem(val id: String, val original: String, val translation: String)

data class VerifySpec(
    val bookTitle: String,
    val author: String?,
    val chapterTitle: String?,
    val locationLabel: String?,
    val sourceLang: String,
    val targetLang: String,
    val items: List<VerifyItem>,
    val contextBefore: List<PromptBlock> = emptyList(),
    val contextAfter: List<PromptBlock> = emptyList(),
    val chapterSummary: String? = null,
    val glossary: List<Pair<String, String>> = emptyList(),
)

object VerifyPromptBuilder {
    private fun langName(code: String) = if (code == "pa") "Punjabi (Gurmukhi script)" else "English"

    private const val SCHEMA =
        "{\"verification\":[{\"id\":\"<paragraph id exactly as given>\",\"verdict\":\"ok\"|\"issues\"," +
            "\"severity\":\"minor\"|\"moderate\"|\"major\"," +
            "\"issues\":[{\"type\":\"meaning|omission|addition|grammar|naturalness|terminology|culture|tone|other\"," +
            "\"description\":\"<what is wrong>\",\"explanation\":\"<why it is wrong>\"}]," +
            "\"suggested_translation\":\"<the full corrected paragraph>\"}]}"

    fun build(spec: VerifySpec): String {
        require(spec.items.isNotEmpty()) { "Cannot build a verification prompt without paragraphs." }
        val src = langName(spec.sourceLang)
        val tgt = langName(spec.targetLang)
        val sb = StringBuilder()
        sb.append("You are a meticulous bilingual literary editor reviewing a translation from $src into $tgt. ")
        sb.append("You are checking existing work, not translating from scratch.\n\n")
        sb.append("BOOK: ").append(spec.bookTitle)
        spec.author?.takeIf { it.isNotBlank() }?.let { sb.append(" — by ").append(it) }
        sb.append('\n')
        val section = listOfNotNull(spec.chapterTitle?.takeIf { it.isNotBlank() }, spec.locationLabel?.takeIf { it.isNotBlank() })
        if (section.isNotEmpty()) sb.append("SECTION: ").append(section.joinToString(" — ")).append('\n')
        sb.append('\n')

        sb.append("For each paragraph, compare the ORIGINAL with the TRANSLATION and check:\n")
        listOf(
            "Whether the meaning is preserved.",
            "Whether any sentence or detail is missing.",
            "Whether the translation makes sense in context.",
            "Whether grammar and spelling are correct.",
            "Whether the $tgt sounds natural.",
            "Whether names and terminology are consistent (follow the glossary if one is given).",
            "Whether cultural expressions are preserved.",
            "Whether the emotional tone and literary voice are preserved.",
            "Whether anything has been mistranslated.",
            "Whether the translation adds information that is not in the original.",
        ).forEachIndexed { i, c -> sb.append(i + 1).append(". ").append(c).append('\n') }
        sb.append('\n')

        sb.append("RULES\n")
        listOf(
            "Only report real problems. Do not rewrite a paragraph just because you would phrase it differently. If it is fine, use verdict \"ok\".",
            "Be honest about uncertainty: if you are not sure something is wrong, say so in the explanation and use severity \"minor\".",
            "severity: \"minor\" = small wording, spelling or style; \"moderate\" = noticeable awkwardness or a small inaccuracy; \"major\" = wrong meaning, missing or invented content.",
            "Give suggested_translation (the FULL corrected paragraph in $tgt, same script) only when you are confident. Otherwise leave it out. Never invent a correction you cannot justify.",
            "Do not change names the glossary fixes. Paragraphs under CONTEXT are for understanding only: do not review them.",
            "Reply with the JSON described under OUTPUT FORMAT and nothing else.",
        ).forEachIndexed { i, r -> sb.append(i + 1).append(". ").append(r).append('\n') }
        sb.append('\n')

        if (spec.glossary.isNotEmpty()) {
            sb.append("GLOSSARY (approved renderings):\n")
            spec.glossary.forEach { (a, b) -> sb.append("- ").append(a).append(" => ").append(b).append('\n') }
            sb.append('\n')
        }
        spec.chapterSummary?.takeIf { it.isNotBlank() }?.let { sb.append("CHAPTER SUMMARY (background only):\n").append(it.trim()).append("\n\n") }
        if (spec.contextBefore.isNotEmpty()) {
            sb.append("CONTEXT — PRECEDING PARAGRAPHS (original text, do not review):\n")
            spec.contextBefore.forEach { sb.append("- ").append(it.text.replace('\n', ' ')).append('\n') }
            sb.append('\n')
        }
        sb.append("PARAGRAPHS TO REVIEW (${spec.items.size}):\n{\"paragraphs\":[\n")
        spec.items.forEachIndexed { i, it ->
            sb.append("  {\"id\":").append(Json.quote(it.id)).append(",\"original\":").append(Json.quote(it.original))
                .append(",\"translation\":").append(Json.quote(it.translation)).append('}')
            sb.append(if (i < spec.items.lastIndex) ",\n" else "\n")
        }
        sb.append("]}\n\n")
        if (spec.contextAfter.isNotEmpty()) {
            sb.append("CONTEXT — FOLLOWING PARAGRAPHS (original text, do not review):\n")
            spec.contextAfter.forEach { sb.append("- ").append(it.text.replace('\n', ' ')).append('\n') }
            sb.append('\n')
        }
        sb.append("OUTPUT FORMAT\nReturn one JSON object inside a single ```json code block, using exactly this structure:\n")
        sb.append(SCHEMA).append('\n')
        sb.append("Include exactly ${spec.items.size} entries, one per paragraph id above, in the same order, copying each id exactly. ")
        sb.append("For a paragraph with no problems write only {\"id\":...,\"verdict\":\"ok\"}. ")
        sb.append("Escape double quotes inside text as \\\" and line breaks as \\n so the JSON stays valid.\n")
        return sb.toString()
    }

    fun buildRepair(expectedIds: List<String>, raw: String, problem: String): String {
        val sb = StringBuilder()
        sb.append("Below is a reply to a translation-review request. I could not use it because: $problem\n\n")
        sb.append("Reformat it WITHOUT changing, adding to or removing any finding, severity or suggested text. ")
        sb.append("Do not review anything yourself, and do not invent a verdict for a paragraph the reply does not mention: leave it out.\n\n")
        sb.append("Return one JSON object inside a single ```json code block, using exactly this structure:\n").append(SCHEMA).append("\n")
        sb.append("Escape double quotes inside text as \\\" and line breaks as \\n.\n\n")
        sb.append("The paragraph ids that should be covered: ").append(expectedIds.joinToString(", ")).append("\n\n")
        sb.append("REPLY TO REFORMAT (between the markers):\n<<<BEGIN REPLY\n").append(raw.trim()).append("\nEND REPLY>>>\n")
        return sb.toString()
    }
}

data class IssueNote(val type: String?, val description: String, val explanation: String?)

data class VerifyFinding(
    val id: String,
    val ok: Boolean,
    val severity: Severity?,
    val issues: List<IssueNote>,
    val suggestion: String?,
)

data class VerifyParse(
    /** Expected paragraph id -> what the AI said. Paragraphs the AI did not mention are NOT here. */
    val findings: Map<String, VerifyFinding>,
    /** Expected ids with no verdict: they stay "not verified". Silence is never treated as "ok". */
    val notCovered: List<String>,
    val unknown: List<String>,
    val truncated: Boolean,
    val recovered: Boolean,
    val warnings: List<String>,
    val error: String?,
) {
    val okCount get() = findings.values.count { it.ok }
    val issueCount get() = findings.values.count { !it.ok }
    val isComplete get() = error == null && notCovered.isEmpty() && !truncated
}

object VerificationParser {
    private val idKeys = listOf("id", "paragraph_id", "para_id", "paragraphId")
    private val verdictKeys = listOf("verdict", "status", "result")
    private val issuesKeys = listOf("issues", "problems", "findings")
    private val textKeys = listOf("description", "issue", "problem", "text", "message")
    private val explainKeys = listOf("explanation", "reason", "why", "details")
    private val typeKeys = listOf("type", "category", "issue_type", "kind")
    private val suggestKeys = listOf("suggested_translation", "suggestion", "corrected_translation", "suggested_correction", "corrected_text", "correction")
    private val severityKeys = listOf("severity", "level", "priority")
    private val containerKeys = listOf("verification", "results", "reviews", "paragraphs", "findings")
    private val okWords = setOf("ok", "pass", "passed", "correct", "good", "fine", "verified", "approved", "accurate", "no issues", "no_issues", "none")

    private class Raw(val id: String, val verdict: String?, val severity: Severity?, val issues: List<IssueNote>, val suggestion: String?)

    private fun field(o: J.Obj, keys: List<String>): J? =
        keys.firstNotNullOfOrNull { k -> o.map.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value }

    private fun str(j: J?): String? = when (j) { is J.Str -> j.v; is J.Num -> j.raw; else -> null }

    private fun entryLike(o: J.Obj) =
        str(field(o, idKeys)) != null &&
            (field(o, verdictKeys) != null || field(o, issuesKeys) != null || field(o, textKeys) != null ||
                field(o, suggestKeys) != null || field(o, severityKeys) != null)

    private fun rawOf(o: J.Obj): Raw? {
        val id = str(field(o, idKeys))?.trim()?.uppercase() ?: return null
        val verdict = str(field(o, verdictKeys))?.trim()?.lowercase()
        val severity = Severity.parse(str(field(o, severityKeys)))
        val issues = ArrayList<IssueNote>()
        when (val iv = field(o, issuesKeys)) {
            is J.Arr -> for (x in iv.items) when (x) {
                is J.Str -> if (x.v.isNotBlank()) issues.add(IssueNote(null, x.v.trim(), null))
                is J.Obj -> {
                    val d = str(field(x, textKeys))?.trim().orEmpty()
                    val e = str(field(x, explainKeys))?.trim()?.takeIf { it.isNotEmpty() }
                    if (d.isNotEmpty() || e != null) issues.add(IssueNote(str(field(x, typeKeys))?.trim()?.takeIf { it.isNotEmpty() }, d.ifEmpty { e!! }, if (d.isEmpty()) null else e))
                }
                else -> {}
            }
            is J.Str -> if (iv.v.isNotBlank()) issues.add(IssueNote(null, iv.v.trim(), null))
            else -> {
                val d = str(field(o, textKeys))?.trim().orEmpty()
                if (d.isNotEmpty()) issues.add(IssueNote(str(field(o, typeKeys))?.trim()?.takeIf { it.isNotEmpty() }, d, str(field(o, explainKeys))?.trim()?.takeIf { it.isNotEmpty() }))
            }
        }
        val sug = str(field(o, suggestKeys))?.trim()?.takeIf { it.isNotEmpty() }
        return Raw(id, verdict, severity, issues, sug)
    }

    private fun fromTree(v: J): List<J.Obj>? = when (v) {
        is J.Arr -> v.items.filterIsInstance<J.Obj>()
        is J.Obj -> {
            val arr = containerKeys.firstNotNullOfOrNull { k -> v.map.entries.firstOrNull { it.key.equals(k, true) }?.value as? J.Arr }
                ?: v.map.values.firstOrNull { it is J.Arr } as? J.Arr
            when {
                arr != null -> arr.items.filterIsInstance<J.Obj>()
                entryLike(v) -> listOf(v)
                else -> null
            }
        }
        else -> null
    }

    private fun candidate(text: String): Pair<String, Boolean> =
        Fences.pickJson(text, listOf("verdict", "verification")).let { it.text to it.unclosed }

    fun parse(rawText: String, expectedIds: List<String>): VerifyParse {
        val text = rawText.trim()
        if (text.isEmpty()) return failure("The pasted reply is empty.", expectedIds)
        val warnings = ArrayList<String>()
        val (cand, unclosed) = candidate(text)
        var objs: List<J.Obj>? = null
        var truncated = false
        var recovered = false
        var strictMsg: String? = null
        var strictAtEnd = false
        try {
            objs = fromTree(JsonReader(cand).parseTop())
            if (objs == null) strictMsg = "The JSON was valid but did not contain a list of findings."
        } catch (e: JsonParseException) {
            strictMsg = e.message; strictAtEnd = e.atEnd
        }
        if (objs == null) {
            val salvaged = ArrayList<J.Obj>()
            var sawEnd = false
            var i = 0
            while (i < cand.length) {
                val j = cand.indexOf('{', i)
                if (j < 0) break
                val r = JsonReader(cand); r.pos = j
                try {
                    val v = r.parseTop()
                    if (v is J.Obj && entryLike(v)) { salvaged.add(v); i = r.pos } else i = j + 1
                } catch (e: JsonParseException) { if (e.atEnd) sawEnd = true; i = j + 1 }
            }
            if (salvaged.isEmpty()) {
                return failure(
                    "Couldn't read this reply${strictMsg?.let { " ($it)" } ?: ""}. " +
                        if (strictAtEnd || unclosed) "It looks cut off before any complete paragraph." else "No paragraph findings were found.",
                    expectedIds, truncated = strictAtEnd || unclosed,
                )
            }
            objs = salvaged
            recovered = true
            truncated = strictAtEnd || sawEnd || unclosed
            warnings.add(
                if (truncated) "The reply looks cut off. ${salvaged.size} complete finding(s) were recovered; the rest are not verified."
                else "The reply was not valid JSON. ${salvaged.size} finding(s) were recovered; check the rest."
            )
        }
        return assemble(objs, expectedIds, warnings, truncated, recovered)
    }

    private fun failure(msg: String, expected: List<String>, truncated: Boolean = false) =
        VerifyParse(emptyMap(), expected, emptyList(), truncated, false, emptyList(), msg)

    private fun assemble(objs: List<J.Obj>, expectedIds: List<String>, warnings: MutableList<String>, truncated: Boolean, recovered: Boolean): VerifyParse {
        val expected = expectedIds.toSet()
        val grouped = LinkedHashMap<String, MutableList<Raw>>()
        val unknown = ArrayList<String>()
        for (o in objs) {
            val r = rawOf(o) ?: continue
            if (r.id in expected) grouped.getOrPut(r.id) { ArrayList() }.add(r) else if (r.id !in unknown) unknown.add(r.id)
        }
        val findings = LinkedHashMap<String, VerifyFinding>()
        for (id in expectedIds) {
            val rs = grouped[id] ?: continue
            val issues = rs.flatMap { it.issues }
            val sev = rs.mapNotNull { it.severity }.maxByOrNull { it.rank }
            val sugs = rs.mapNotNull { it.suggestion }.distinct()
            val saysOk = rs.all { it.verdict != null && it.verdict in okWords }
            val hasIssue = issues.isNotEmpty() || sev != null || sugs.isNotEmpty() || rs.any { it.verdict != null && it.verdict !in okWords }
            when {
                hasIssue -> {
                    if (sev == null) warnings.add("$id: no severity was given; treated as Moderate.")
                    if (sugs.size > 1) warnings.add("$id: several different corrections were suggested; the first is offered.")
                    findings[id] = VerifyFinding(id, false, sev ?: Severity.MODERATE, issues, sugs.firstOrNull())
                }
                saysOk -> findings[id] = VerifyFinding(id, true, null, emptyList(), null)
                else -> warnings.add("$id: the entry had no verdict, so it stays not verified.")
            }
        }
        if (unknown.isNotEmpty()) warnings.add("Entries for unknown ids were ignored: ${unknown.joinToString()}.")
        val notCovered = expectedIds.filter { it !in findings }
        return VerifyParse(findings, notCovered, unknown, truncated, recovered, warnings, null)
    }
}

object Corrections {
    private fun flat(s: String) = TextUtils.normalizeTranslation(s).replace(Regex("\\s+"), " ")

    fun sameText(a: String, b: String) = flat(a) == flat(b)

    /** A suggestion that is blank or identical to the current text is not a correction. */
    fun effectiveSuggestion(current: String, suggested: String?): String? {
        val s = suggested?.let(TextUtils::normalizeTranslation)?.takeIf { it.isNotEmpty() } ?: return null
        return if (sameText(s, current)) null else s
    }
}

object Resolve {
    const val ACCEPT = "ACCEPT"
    const val EDIT = "EDIT"
    const val REJECT = "REJECT"

    /** [newText] is null when the saved translation stays as it is. */
    data class Result(val newText: String?, val findingState: String)

    /** Returns null when the action is not allowed (e.g. accepting a finding that has no suggestion). */
    fun apply(action: String, current: String, suggestion: String?, edited: String?): Result? = when (action) {
        ACCEPT -> Corrections.effectiveSuggestion(current, suggestion)?.let { Result(it, FindingStates.ACCEPTED) }
        EDIT -> edited?.let { Corrections.effectiveSuggestion(current, it) }?.let { Result(it, FindingStates.EDITED) }
        REJECT -> Result(null, FindingStates.REJECTED)
        else -> null
    }

    fun stateAfter(action: String, remainingOpenSeverities: List<String>): String =
        if (action == REJECT) VerifyStates.fromOpenSeverities(remainingOpenSeverities) else VerifyStates.VERIFIED
}

/** Remembers which exact translation text a prompt was built from, so a stale reply cannot overwrite newer work. */
object Fingerprint {
    fun hash(text: String): String = Integer.toHexString(TextUtils.normalizeTranslation(text).hashCode())
    fun encode(texts: Map<Long, String>): String = texts.entries.joinToString(",") { "${it.key}:${hash(it.value)}" }
    fun decode(s: String?): Map<Long, String> =
        if (s.isNullOrBlank()) emptyMap() else s.split(',').mapNotNull { p ->
            val i = p.indexOf(':'); if (i < 0) null else p.substring(0, i).toLongOrNull()?.let { it to p.substring(i + 1) }
        }.toMap()
    fun matches(snapshot: Map<Long, String>, blockId: Long, currentText: String) = snapshot[blockId] == hash(currentText)
}

data class VerifyTarget(val blockId: Long, val paraId: String, val translationId: Long, val currentText: String)
data class OutcomeFinding(val severity: Severity, val issueText: String, val explanation: String?, val suggestion: String?)
data class VerifyOutcome(val target: VerifyTarget, val newState: String, val finding: OutcomeFinding?)
data class VerifyPlan(val outcomes: List<VerifyOutcome>, val stale: List<String>)

object VerifyPlanner {
    /**
     * Turns a parsed reply into database changes. Paragraphs the AI did not mention are untouched, and a paragraph whose
     * translation changed since the prompt was made is skipped (reported as stale), never overwritten.
     */
    fun plan(targets: List<VerifyTarget>, parse: VerifyParse, snapshot: Map<Long, String>): VerifyPlan {
        val outcomes = ArrayList<VerifyOutcome>()
        val stale = ArrayList<String>()
        for (t in targets) {
            val f = parse.findings[t.paraId] ?: continue
            if (!Fingerprint.matches(snapshot, t.blockId, t.currentText)) { stale.add(t.paraId); continue }
            if (f.ok) {
                outcomes.add(VerifyOutcome(t, VerifyStates.VERIFIED, null))
            } else {
                val sev = f.severity ?: Severity.MODERATE
                val issueText = f.issues.joinToString("\n") { n -> (n.type?.let { "[$it] " } ?: "") + n.description }
                    .ifBlank { "The AI flagged this paragraph but gave no details." }
                val expl = f.issues.mapNotNull { it.explanation }.joinToString("\n").ifBlank { null }
                outcomes.add(
                    VerifyOutcome(t, VerifyStates.forSeverity(sev), OutcomeFinding(sev, issueText, expl, Corrections.effectiveSuggestion(t.currentText, f.suggestion)))
                )
            }
        }
        return VerifyPlan(outcomes, stale)
    }
}
