package com.livetranslate.gemini.prompt

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
        assertTrue(prompt.contains("one-way"))
        assertTrue(prompt.contains("English"))
        assertTrue(prompt.contains("Русский"))
    }

    @Test
    fun testDialoguePromptGeneration() {
        val prompt = PromptBuilder.buildSystemPrompt(
            mode = TranslationMode.DIALOGUE,
            ourLanguageCode = "ru",
            opponentLanguageCode = "zh"
        )
        assertTrue(prompt.contains("bidirectional"))
        assertTrue(prompt.contains("Русский"))
        assertTrue(prompt.contains("Китайский"))
    }
}
