package com.livetranslate.gemini.prompt

import com.livetranslate.core.model.Language
import com.livetranslate.core.model.TranslationMode
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun testSoloPromptGeneration() {
        val prompt = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.SOLO,
            ourLanguageCode = "ru",
            opponentLanguageCode = "en"
        )
        assertTrue(prompt.contains(Language.findByCode("en").nativeName))
        assertTrue(prompt.contains(Language.findByCode("ru").nativeName))
    }

    @Test
    fun testDialoguePromptGeneration() {
        val prompt = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.DIALOGUE,
            ourLanguageCode = "ru",
            opponentLanguageCode = "zh"
        )
        assertTrue(prompt.contains("bidirectional"))
        assertTrue(prompt.contains(Language.findByCode("ru").nativeName))
        assertTrue(prompt.contains(Language.findByCode("zh").nativeName))
    }
}
