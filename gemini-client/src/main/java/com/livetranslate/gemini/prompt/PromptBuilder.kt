package com.livetranslate.gemini.prompt

import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode

object PromptBuilder {

    fun buildSystemPrompt(
        mode: TranslationMode,
        ourLanguageCode: String,
        opponentLanguageCode: String
    ): String {
        val ourLang = Language.findByCode(ourLanguageCode).name
        val oppLang = Language.findByCode(opponentLanguageCode).name

        return when (mode) {
            TranslationMode.SOLO -> """
You are a real-time speech-to-speech interpreter from $oppLang to $ourLang.
RULE: Output ONLY the spoken translation in $ourLang.
Never explain, never transcribe, never output preamble. Speak ONLY the translated words.
""".trimIndent()

            TranslationMode.DIALOGUE -> """
You are a real-time bidirectional speech interpreter between $ourLang and $oppLang.
- If input is in $ourLang, speak ONLY the translation in $oppLang.
- If input is in $oppLang, speak ONLY the translation in $ourLang.
RULE: Speak ONLY the exact translated sentence. Absolutely no conversational filler or commentary.
""".trimIndent()
        }
    }
}
