package com.punjabibooktranslator

import android.app.Application
import com.punjabibooktranslator.data.AppDatabase
import com.punjabibooktranslator.data.ContextRepository
import com.punjabibooktranslator.data.BackupRepository
import com.punjabibooktranslator.data.ExportRepository
import com.punjabibooktranslator.data.GlossaryRepository
import com.punjabibooktranslator.data.JobRepository
import com.punjabibooktranslator.data.ReviewRepository
import com.punjabibooktranslator.data.PrefsRepository
import com.punjabibooktranslator.data.TranslationRepository
import com.punjabibooktranslator.importer.BookImporter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BookApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
        container.start()
    }
}

/** Small hand-rolled service locator; avoids a DI framework for a project this size. */
class AppContainer(app: Application) {
    /** The text the in-app AI window offers to copy. Set just before the window opens. */
    @Volatile var geminiPrompt: String = ""
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.build(app)
    val prefs = PrefsRepository(app)
    val importer = BookImporter(app, db, appScope)
    val translations = TranslationRepository(db)
    val jobs = JobRepository(db)
    val glossary = GlossaryRepository(db)
    val exports = ExportRepository(db, app)
    val backup = BackupRepository(db, app, translations, glossary)
    val review = ReviewRepository(db)
    val context = ContextRepository(db)

    fun start() {
        appScope.launch { db.bookDao().markInterruptedImports() }
    }
}
