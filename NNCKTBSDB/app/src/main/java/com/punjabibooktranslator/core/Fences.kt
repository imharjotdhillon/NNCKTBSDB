package com.punjabibooktranslator.core

/** Finds the JSON inside a chat reply that may wrap it in ``` code fences, or may be cut off before the closing fence. */
internal object Fences {
    class Block(val text: String, val unclosed: Boolean)

    private val MARKER = Regex("```[A-Za-z0-9_-]*[ \\t]*\\r?\\n?")

    /** Every fenced block, in order. A block with no closing fence runs to the end of the text and is marked unclosed. */
    fun blocks(text: String): List<Block> {
        val marks = MARKER.findAll(text).toList()
        val out = ArrayList<Block>()
        var k = 0
        while (k < marks.size) {
            val closed = k + 1 < marks.size
            out.add(Block(text.substring(marks[k].range.last + 1, if (closed) marks[k + 1].range.first else text.length), !closed))
            k += 2
        }
        return out
    }

    /**
     * The block most likely to hold the reply's JSON: one mentioning a [markers] word, else the first that starts like JSON,
     * else everything from the first `{` or `[` in the text.
     */
    fun pickJson(text: String, markers: List<String>): Block {
        val all = blocks(text)
        all.firstOrNull { b -> markers.any { b.text.contains(it) } }?.let { return it }
        all.firstOrNull { it.text.trimStart().let { t -> t.startsWith("{") || t.startsWith("[") } }?.let { return it }
        val first = text.indexOfFirst { it == '{' || it == '[' }
        return Block(if (first >= 0) text.substring(first) else text, false)
    }
}
