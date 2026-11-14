package com.punjabibooktranslator.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        TextBlockEntity::class,
        TranslationEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        TranslationJobEntity::class,
        GlossaryEntity::class,
        RevisionEntity::class,
        FindingEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readerDao(): ReaderDao
    abstract fun jobDao(): JobDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun revisionDao(): RevisionDao
    abstract fun findingDao(): FindingDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "books.db")
                // Never use fallbackToDestructiveMigration: it would erase translations.
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

/** Add one Migration per schema version bump. Schemas are exported to app/schemas/. */
object Migrations {
    /** v1 -> v2: per-book translation style. Existing books default to LITERARY; nothing else changes. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN translationStyle TEXT NOT NULL DEFAULT 'LITERARY'")
        }
    }

    /** v2 -> v3: translation job queue. Purely additive: no existing table is touched. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `translation_jobs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `label` TEXT NOT NULL, " +
                    "`mode` TEXT NOT NULL, `blockIds` TEXT NOT NULL, `paraCount` INTEGER NOT NULL, `chars` INTEGER NOT NULL, " +
                    "`firstOrder` INTEGER NOT NULL, `lastOrder` INTEGER NOT NULL, `firstPage` INTEGER, `lastPage` INTEGER, " +
                    "`partIndex` INTEGER NOT NULL, `partCount` INTEGER NOT NULL, `batchSize` INTEGER NOT NULL, " +
                    "`sourceLang` TEXT NOT NULL, `targetLang` TEXT NOT NULL, `style` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`prompt` TEXT, `promptBlockIds` TEXT, `responseText` TEXT, `lastError` TEXT, " +
                    "`sortKey` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_jobs_bookId_status` ON `translation_jobs` (`bookId`, `status`)")
        }
    }

    /** v3 -> v4: glossary, revision history, verification findings, per-paragraph verify state. Purely additive. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE translations ADD COLUMN verifyState TEXT NOT NULL DEFAULT 'NOT_VERIFIED'")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `glossary_terms` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `term` TEXT NOT NULL, " +
                    "`termKey` TEXT NOT NULL, `translation` TEXT NOT NULL, `type` TEXT NOT NULL, `explanation` TEXT, " +
                    "`approved` INTEGER NOT NULL, `previousTranslation` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_glossary_terms_bookId_termKey` ON `glossary_terms` (`bookId`, `termKey`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `translation_revisions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `textBlockId` INTEGER NOT NULL, `translatedText` TEXT NOT NULL, " +
                    "`revision` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`textBlockId`) REFERENCES `text_blocks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_translation_revisions_textBlockId` ON `translation_revisions` (`textBlockId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `verification_findings` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `textBlockId` INTEGER NOT NULL, " +
                    "`severity` TEXT NOT NULL, `issueText` TEXT NOT NULL, `explanation` TEXT, `suggestion` TEXT, `state` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, " +
                    "FOREIGN KEY(`textBlockId`) REFERENCES `text_blocks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_verification_findings_textBlockId` ON `verification_findings` (`textBlockId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_verification_findings_bookId_state` ON `verification_findings` (`bookId`, `state`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
