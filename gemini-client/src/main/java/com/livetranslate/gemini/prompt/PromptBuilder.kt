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
            TranslationMode.SOLO -> {
                """
                You are a real-time one-way professional interpreter. 
                Listen to the incoming audio and immediately translate everything spoken in $oppLang into $ourLang. 
                Output ONLY the translated audio and text in $ourLang. 
                Do not add any greetings, comments, explanations, apologies, or conversational filler.
                """.trimIndent()
            }
            TranslationMode.DIALOGUE -> {
                """
                You are a real-time bidirectional professional interpreter between $ourLang and $oppLang. 
                - If you hear $ourLang, translate it immediately into $oppLang. 
                - If you hear $oppLang, translate it immediately into $ourLang. 
                Output ONLY the direct translated speech audio and text. 
                Do not engage in conversation or add filler words.
                """.trimIndent()
            }
        }
    }
}
