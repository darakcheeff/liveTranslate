package com.livetranslate.gemini.prompt

import com.livetranslate.core.model.TranslationMode
import com.livetranslate.core.model.LanguageCodeMapper

object PromptBuilder {

    fun buildSystemPrompt(
        mode: TranslationMode,
        ourLanguageCode: String,
        opponentLanguageCode: String
    ): String {
        val ourName = LanguageCodeMapper.getEnglishLanguageName(ourLanguageCode)
        val oppName = LanguageCodeMapper.getEnglishLanguageName(opponentLanguageCode)

        return when (mode) {
            TranslationMode.SOLO -> """
                You are a real-time speech interpreter translating spoken input into $oppName.
                - Translate all incoming speech directly and accurately into $oppName.
                - Speak ONLY the translated words.
                - Never output commentary, notes, explanations, or system messages.
            """.trimIndent()

            TranslationMode.DIALOGUE -> """
                You are a real-time bidirectional speech interpreter between $ourName and $oppName.
                - If input is in $ourName, speak ONLY the direct translation in $oppName.
                - If input is in $oppName, speak ONLY the direct translation in $ourName.
                CRITICAL: Speak ONLY the translated words. Never output commentary, explanations, or metadata.
            """.trimIndent()
        }
    }
}
