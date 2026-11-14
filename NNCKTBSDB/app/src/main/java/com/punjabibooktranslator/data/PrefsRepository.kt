package com.punjabibooktranslator.data

import android.content.Context
import com.punjabibooktranslator.core.AiProvider
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReaderFont { SANS, SERIF, GURMUKHI }

data class ReaderPrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: Float = 18f,
    val font: ReaderFont = ReaderFont.SANS,
    val lineSpacing: Float = 1.6f,
    val paragraphSpacing: Float = 14f,
    val margin: Float = 20f,
    val justify: Boolean = false,
    /** 0.05..1.0, or -1 to follow the system brightness. */
    val brightness: Float = -1f,
    /** Which AI chat website the in-app window opens. */
    val provider: AiProvider = AiProvider.GEMINI,
)

private val Context.dataStore by preferencesDataStore(name = "reader_prefs")

class PrefsRepository(private val context: Context) {
    private val kTheme = stringPreferencesKey("theme")
    private val kFontSize = floatPreferencesKey("font_size")
    private val kFont = stringPreferencesKey("font")
    private val kLine = floatPreferencesKey("line_spacing")
    private val kPara = floatPreferencesKey("paragraph_spacing")
    private val kMargin = floatPreferencesKey("margin")
    private val kJustify = booleanPreferencesKey("justify")
    private val kBrightness = floatPreferencesKey("brightness")
    private val kProvider = stringPreferencesKey("ai_provider")

    val prefs: Flow<ReaderPrefs> = context.dataStore.data.map { it.toPrefs() }

    suspend fun update(transform: ReaderPrefs.() -> ReaderPrefs) {
        context.dataStore.edit { p ->
            val n = p.toPrefs().transform()
            p[kTheme] = n.themeMode.name
            p[kFontSize] = n.fontSize
            p[kFont] = n.font.name
            p[kLine] = n.lineSpacing
            p[kPara] = n.paragraphSpacing
            p[kMargin] = n.margin
            p[kJustify] = n.justify
            p[kBrightness] = n.brightness
            p[kProvider] = n.provider.name
        }
    }

    private fun Preferences.toPrefs(): ReaderPrefs {
        val d = ReaderPrefs()
        return ReaderPrefs(
            themeMode = this[kTheme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            fontSize = this[kFontSize] ?: d.fontSize,
            font = this[kFont]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: d.font,
            lineSpacing = this[kLine] ?: d.lineSpacing,
            paragraphSpacing = this[kPara] ?: d.paragraphSpacing,
            margin = this[kMargin] ?: d.margin,
            justify = this[kJustify] ?: d.justify,
            brightness = this[kBrightness] ?: d.brightness,
            provider = AiProvider.fromKey(this[kProvider]),
        )
    }
}
