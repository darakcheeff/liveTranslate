package com.livetranslate.gemini.failover

import com.livetranslate.core.model.GeminiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolManagerTest {

    @Test
    fun testKeyRotationCyclesThroughKeys() {
        val config = GeminiConfig(
            apiKeys = listOf("key1", "key2", "key3"),
            preferredModels = listOf("modelA", "modelB")
        )
        val manager = KeyPoolManager(config)

        assertEquals("key1", manager.getActiveApiKey())
        assertEquals("modelA", manager.getActiveModel())

        // Failover 1 -> key2
        val res1 = manager.rotateOnFailure("429 Too Many Requests")
        assertTrue(res1 is FailoverResult.KeyRotated)
        assertEquals("key2", manager.getActiveApiKey())
        assertEquals("modelA", manager.getActiveModel())

        // Failover 2 -> key3
        val res2 = manager.rotateOnFailure("429 Too Many Requests")
        assertTrue(res2 is FailoverResult.KeyRotated)
        assertEquals("key3", manager.getActiveApiKey())
        assertEquals("modelA", manager.getActiveModel())

        // Failover 3 -> Cascade to modelB and key1
        val res3 = manager.rotateOnFailure("429 Too Many Requests")
        assertTrue(res3 is FailoverResult.ModelCascaded)
        assertEquals("key1", manager.getActiveApiKey())
        assertEquals("modelB", manager.getActiveModel())
    }
}
