package com.punjabibooktranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.punjabibooktranslator.data.ReaderPrefs
import com.punjabibooktranslator.ui.AppRoot
import com.punjabibooktranslator.ui.BookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BookApp).container
        setContent {
            val prefs by container.prefs.prefs.collectAsStateWithLifecycle(initialValue = ReaderPrefs())
            BookTheme(prefs.themeMode) { AppRoot(container) }
        }
    }
}
