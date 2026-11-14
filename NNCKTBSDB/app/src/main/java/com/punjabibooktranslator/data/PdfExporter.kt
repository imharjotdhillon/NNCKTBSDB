package com.punjabibooktranslator.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.punjabibooktranslator.R
import com.punjabibooktranslator.core.ExportDoc
import com.punjabibooktranslator.core.ExportItem
import com.punjabibooktranslator.core.ItemKind
import java.io.OutputStream

/**
 * Draws the document with Android's own text layout (StaticLayout), which shapes Gurmukhi correctly, using the bundled Noto Sans
 * Gurmukhi font. Text is laid out per paragraph and drawn line by line, so a paragraph can continue on the next page.
 * The PDF contains real text (selectable), not images. NOT TESTED on a device: check Gurmukhi conjuncts, matras and Latin fallback.
 */
class PdfExporter(private val context: Context) {
    private val pageW = 595   // A4 in points
    private val pageH = 842
    private val margin = 54
    private val textW = pageW - margin * 2

    private fun paint(size: Float, bold: Boolean, color: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = ResourcesCompat.getFont(context, if (bold) R.font.noto_sans_gurmukhi_bold else R.font.noto_sans_gurmukhi_regular) ?: Typeface.DEFAULT
    }

    fun write(doc: ExportDoc, out: OutputStream) {
        val pdf = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val bottom = (pageH - margin).toFloat()
        val footer = paint(9f, false, Color.GRAY)

        fun finishPage() {
            val p = page ?: return
            p.canvas.drawText("${pageNo}", pageW / 2f, pageH - 24f, footer)
            pdf.finishPage(p); page = null
        }
        fun newPage() {
            finishPage()
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
        }

        fun block(text: String, tp: TextPaint, indent: Int = 0, gapAfter: Float = 6f) {
            val w = textW - indent
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.3f).setIncludePad(false).build()
            for (i in 0 until layout.lineCount) {
                val top = layout.getLineTop(i); val bot = layout.getLineBottom(i)
                val h = (bot - top).toFloat()
                if (page == null || y + h > bottom) newPage()
                val c = page!!.canvas
                c.save()
                c.translate((margin + indent).toFloat(), y - top)
                c.clipRect(0f, top.toFloat(), w.toFloat(), bot.toFloat())
                layout.draw(c)
                c.restore()
                y += h
            }
            y += gapAfter
        }

        val title = paint(22f, true, Color.BLACK)
        val chapter = paint(18f, true, Color.BLACK)
        val body = paint(12f, false, Color.BLACK)
        val orig = paint(10f, false, Color.DKGRAY)
        val missing = paint(12f, false, Color.rgb(170, 0, 0))

        newPage()
        block(doc.title, title, gapAfter = 4f)
        doc.author?.takeIf { it.isNotBlank() }?.let { block(it, paint(13f, false, Color.DKGRAY), gapAfter = 12f) }

        doc.chapters.forEachIndexed { ci, ch ->
            if (ci > 0 || y > margin + 80) newPage()
            ch.title?.takeIf { it.isNotBlank() }?.let { block(it, chapter, gapAfter = 10f) }
            for (item in ch.items) drawItem(item, ::block, body, orig, missing, paint(14f, true, Color.BLACK))
        }
        finishPage()
        pdf.writeTo(out)
        pdf.close()
    }

    private fun drawItem(
        item: ExportItem, block: (String, TextPaint, Int, Float) -> Unit,
        body: TextPaint, orig: TextPaint, missing: TextPaint, heading: TextPaint,
    ) {
        val tp = if (item.missing) missing else if (item.kind == ItemKind.HEADING) heading else body
        item.original?.let { block(it, orig, 0, 2f) }
        val prefix = if (item.kind == ItemKind.LIST_ITEM) "• " else ""
        val indent = if (item.kind == ItemKind.QUOTE) 18 else 0
        block(prefix + item.translation.orEmpty(), tp, indent, 9f)
    }
}
