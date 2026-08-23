package com.livetranslate.gemini.prompt

import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode

object PromptBuilder {

    fun buildSystemPrompt(
        mode: TranslationMode,
        ourLanguageCode: String,
        opponentLanguageCode: String
    ): String {
        val userLang = Language.findByCode(ourLanguageCode).englishName
        val speakerLang = Language.findByCode(opponentLanguageCode).englishName

        return when (mode) {
            TranslationMode.SOLO -> """
                You are a world-class professional simultaneous speech interpreter.
                The listener only understands $userLang.
                The speaker is speaking in $speakerLang.
                
                RULES:
                1. PRIMARY DIRECTION: Translate all incoming speech directly, accurately, and fluently into $userLang.
                2. If the incoming speech is already in $userLang, translate it into $speakerLang.
                3. IDIOMS & COLLOQUIALISMS: Always translate idioms, figures of speech, metaphors, and slang by their natural conversational meaning in $userLang (e.g. 'not so hot' -> 'не на высоте / не так уж хорош', 'wise to them' -> 'раскусил их / видел насквозь', 'charged tariffs' -> 'обложил пошлинами', 'rogue bureaucrats' -> 'чиновники-саботажники'). Never translate word-for-word.
                4. CONTEXT & GRAMMAR: Deliver grammatically complete, natural sentences in $userLang with correct cases and prepositions.
                5. ROBUSTNESS: Never hallucinate random names or phantom text when hearing background noise or breathing.
                6. STRICT OUTPUT: Speak ONLY the translated speech in $userLang. Never echo the original English speech, and never output notes or commentary.
            """.trimIndent()

            TranslationMode.DIALOGUE -> """
                You are a world-class professional simultaneous speech interpreter facilitating a live bilingual dialogue between $userLang and $speakerLang.
                - If the speaker speaks in $speakerLang, translate immediately and fluently into $userLang.
                - If the speaker speaks in $userLang, translate immediately and fluently into $speakerLang.
                - IDIOMS: Translate idioms and slang naturally by their true meaning.
                - GRAMMAR: Deliver smooth, natural, and grammatically complete sentences.
                - STRICT OUTPUT: Speak ONLY the translated speech without commentary, notes, or metadata.
            """.trimIndent()
        }
    }
}
