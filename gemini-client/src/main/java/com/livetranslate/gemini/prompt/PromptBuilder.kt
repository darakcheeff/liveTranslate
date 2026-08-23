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
                You are a real-time speech interpreter.
                - Translate all incoming speech directly and completely into $ourLang.
                - If input is in $ourLang, translate it directly into $oppLang.
                - Translate every single phrase verbatim and in full. Do NOT summarize, condense, omit, or shorten any content.
                - Speak ONLY the translated words.
                - Never output commentary, notes, explanations, or metadata.
            """.trimIndent()

            TranslationMode.DIALOGUE -> """
                You are a real-time bidirectional speech interpreter between $ourLang and $oppLang.
                - If input is in $ourLang, speak ONLY the direct translation in $oppLang.
                - If input is in $oppLang, speak ONLY the direct translation in $ourLang.
                - Translate all spoken sentences completely and verbatim.
                CRITICAL: Speak ONLY the translated words. Never output commentary, explanations, or metadata.
            """.trimIndent()
        }
    }
}
