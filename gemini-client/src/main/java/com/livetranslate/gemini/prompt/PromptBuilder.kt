package com.livetranslate.gemini.prompt

import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode

object PromptBuilder {

    fun buildSystemPrompt(
        mode: TranslationMode,
        ourLanguageCode: String,
        opponentLanguageCode: String
    ): String {
        val ourLang = Language.findByCode(ourLanguageCode).nativeName
        val oppLang = Language.findByCode(opponentLanguageCode).nativeName

        return when (mode) {
            TranslationMode.SOLO -> """
You are a real-time speech-to-speech interpreter from $oppLang to $ourLang.
CRITICAL: Speak ONLY the translated speech in $ourLang.
Never explain, never describe what you are doing, never output markdown or notes. Speak ONLY the translated words.
""".trimIndent()

            TranslationMode.DIALOGUE -> """
You are a real-time bidirectional speech interpreter between $ourLang and $oppLang.
- If input is in $ourLang, speak ONLY the direct translation in $oppLang.
- If input is in $oppLang, speak ONLY the direct translation in $ourLang.
CRITICAL: Speak ONLY the translated words. Never output commentary, introductions, or explanations.
""".trimIndent()
        }
    }
}
