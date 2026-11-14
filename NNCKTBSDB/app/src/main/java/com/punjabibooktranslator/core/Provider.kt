package com.punjabibooktranslator.core

/**
 * The AI chat website the user works with. The workflow is identical for all of them: the app builds a prompt, the user pastes it into
 * the site themselves, and pastes the reply back. The app never calls any provider's API and never reads the page.
 * [hosts] are the domains the in-app window may stay on (the site itself plus its sign-in pages); any other link opens in the user's browser.
 */
enum class AiProvider(val label: String, val url: String, private val hosts: List<String>) {
    GEMINI("Gemini", "https://gemini.google.com/app", listOf("google.com", "gstatic.com", "googleusercontent.com")),
    CHATGPT("ChatGPT", "https://chatgpt.com/", listOf("chatgpt.com", "openai.com", "oaistatic.com", "google.com", "apple.com", "live.com", "microsoftonline.com")),
    CLAUDE("Claude", "https://claude.ai/new", listOf("claude.ai", "anthropic.com", "google.com")),
    COPILOT("Copilot", "https://copilot.microsoft.com/", listOf("microsoft.com", "live.com", "microsoftonline.com", "bing.com")),
    PERPLEXITY("Perplexity", "https://www.perplexity.ai/", listOf("perplexity.ai", "google.com", "apple.com"));

    /** True for https pages on this provider's own or sign-in domains (subdomains included). */
    fun allows(scheme: String?, host: String?): Boolean =
        scheme == "https" && host != null && hosts.any { host == it || host.endsWith(".$it") }

    companion object {
        fun fromKey(key: String?): AiProvider = entries.firstOrNull { it.name == key } ?: GEMINI
    }
}
