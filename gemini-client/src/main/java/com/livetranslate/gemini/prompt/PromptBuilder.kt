package com.livetranslate.gemini.prompt

import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode

object PromptBuilder {

    fun buildSystemPrompt(
        mode: TranslationMode,
        ourLanguageCode: String,
        opponentLanguageCode: String
    ): String {
        val ourLang = Language.findByCode(ourLanguageCode).englishName
        val oppLang = Language.findByCode(opponentLanguageCode).englishName

        return when (mode) {
            TranslationMode.SOLO -> """
                You are a professional real-time simultaneous speech interpreter.
                - Translate all incoming speech directly, fluently, and completely into $ourLang.
                - If input is already in $ourLang, translate it directly into $oppLang.
                - Translate full sentences with natural grammar and complete meaning.
                - CRITICAL: Speak ONLY the translated words. Never output commentary, explanations, or metadata.
            """.trimIndent()

            TranslationMode.DIALOGUE -> """
                You are a real-time bidirectional speech interpreter between $ourLang and $oppLang.
                - If input is in $ourLang, speak ONLY the direct translation in $oppLang.
                - If input is in $oppLang, speak ONLY the direct translation in $ourLang.
                - Translate all spoken sentences completely and accurately.
                CRITICAL: Speak ONLY the translated words. Never output commentary, explanations, or metadata.
            """.trimIndent()
        }
    }
}
