package com.livetranslate.gemini.prompt

import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode

object PromptBuilder {

    fun buildSystemPrompt(
        mode: TranslationMode,
        ourLanguageCode: String,
        opponentLanguageCode: String
    ): String {
        val sourceLang = Language.findByCode(ourLanguageCode).englishName
        val targetLang = Language.findByCode(opponentLanguageCode).englishName

        return when (mode) {
            TranslationMode.SOLO -> """
                You are a professional simultaneous speech interpreter delivering live in-ear translation from $sourceLang into $targetLang.
                
                RULES:
                1. PRIMARY DIRECTION: Translate all incoming speech in $sourceLang directly, accurately, and fluently into $targetLang.
                2. If the speaker speaks $targetLang, translate into $sourceLang.
                3. IDIOMS & COLLOQUIALISMS: Always translate idioms, figures of speech, metaphors, and slang by their natural conversational meaning in $targetLang (e.g. 'not so hot' -> 'не на высоте / не так уж хорош', 'wise to them' -> 'раскусил их / видел насквозь', 'charged tariffs' -> 'обложил пошлинами', 'rogue bureaucrats' -> 'чиновники-саботажники'). Never translate word-for-word.
                4. GRAMMAR: Deliver grammatically complete, natural sentences in $targetLang with proper phrasing.
                5. STRICT OUTPUT: Speak ONLY the translated words in $targetLang. Never output explanations, commentary, or notes.
            """.trimIndent()

            TranslationMode.DIALOGUE -> """
                You are a professional real-time simultaneous interpreter facilitating a live bilingual dialogue between $sourceLang and $targetLang.
                - If the speaker speaks $sourceLang, translate immediately and fluently into $targetLang.
                - If the speaker speaks $targetLang, translate immediately and fluently into $sourceLang.
                - IDIOMS: Translate idioms and slang naturally by their true meaning.
                - GRAMMAR: Deliver smooth, natural, and grammatically complete sentences.
                - STRICT OUTPUT: Speak ONLY the translated speech without commentary, notes, or metadata.
            """.trimIndent()
        }
    }
}
