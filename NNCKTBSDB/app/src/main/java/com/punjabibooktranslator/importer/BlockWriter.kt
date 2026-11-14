package com.punjabibooktranslator.importer

import androidx.room.withTransaction
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.data.AppDatabase
import com.punjabibooktranslator.data.BlockStatus
import com.punjabibooktranslator.data.ChapterEntity
import com.punjabibooktranslator.data.TextBlockEntity

/**
 * Assigns stable paragraph IDs and contiguous reading order, buffers blocks and saves them
 * in transactions so a book never has to be held in memory.
 */
class BlockWriter(private val db: AppDatabase, private val bookId: Long) {
    private val buffer = ArrayList<TextBlockEntity>()
    private var order = 0
    private var chapterOrder = 0
    private var chapterId: Long? = null

    val blockCount: Int get() = order

    suspend fun startChapter(title: String, resourceHref: String?): Long {
        flush()
        val id = db.readerDao().insertChapter(
            ChapterEntity(bookId = bookId, title = title, chapterOrder = chapterOrder++, resourceHref = resourceHref)
        )
        chapterId = id
        return id
    }

    suspend fun add(
        text: String,
        blockType: String,
        pdfPage: Int?,
        resourceHref: String?,
        locator: String?,
        format: String?,
        status: String = BlockStatus.OK,
    ) {
        buffer.add(
            TextBlockEntity(
                bookId = bookId,
                chapterId = chapterId,
                paraId = TextUtils.paraId(order),
                originalText = text,
                textOrder = order,
                pdfPage = pdfPage,
                resourceHref = resourceHref,
                locator = locator,
                blockType = blockType,
                format = format,
                extractionStatus = status,
            )
        )
        order++
        if (buffer.size >= 200) flush()
    }

    suspend fun flush() {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        db.withTransaction { db.readerDao().insertBlocks(batch) }
    }
}
