package com.punjabibooktranslator

import com.punjabibooktranslator.core.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {
    @Test fun staysOnOwnAndSignInDomainsOnly() {
        assertTrue(AiProvider.GEMINI.allows("https", "gemini.google.com"))
        assertTrue(AiProvider.CHATGPT.allows("https", "auth.openai.com"))
        assertFalse(AiProvider.CHATGPT.allows("https", "evil-chatgpt.com"))
        assertFalse(AiProvider.CLAUDE.allows("https", "notclaude.ai"))
        assertFalse(AiProvider.GEMINI.allows("http", "gemini.google.com"))
        assertFalse(AiProvider.GEMINI.allows("https", null))
        assertFalse(AiProvider.GEMINI.allows("intent", "google.com"))
    }

    @Test fun unknownOrMissingKeyFallsBackToGemini() {
        assertEquals(AiProvider.GEMINI, AiProvider.fromKey(null)); assertEquals(AiProvider.GEMINI, AiProvider.fromKey("nope"))
        assertEquals(AiProvider.CLAUDE, AiProvider.fromKey("CLAUDE"))
        assertTrue(AiProvider.entries.all { it.url.startsWith("https://") })
    }
}
