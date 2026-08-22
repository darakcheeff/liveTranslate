package com.livetranslate.gemini.failover

import com.livetranslate.core.model.GeminiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KeyPoolManager(private var config: GeminiConfig) {

    private val _currentKeyIndex = MutableStateFlow(config.currentKeyIndex)
    val currentKeyIndex: StateFlow<Int> = _currentKeyIndex.asStateFlow()

    private val _currentModelIndex = MutableStateFlow(0)
    val currentModelIndex: StateFlow<Int> = _currentModelIndex.asStateFlow()

    private val models: List<String>
        get() = config.preferredModels.ifEmpty { GeminiConfig.DEFAULT_MODELS }

    fun updateConfig(newConfig: GeminiConfig) {
        config = newConfig
        if (_currentKeyIndex.value >= newConfig.apiKeys.size) {
            _currentKeyIndex.value = 0
        }
    }

    fun getActiveApiKey(): String? {
        if (config.apiKeys.isEmpty()) return null
        val idx = _currentKeyIndex.value.coerceIn(0, config.apiKeys.size - 1)
        return config.apiKeys[idx]
    }

    fun getActiveModel(): String {
        val modelList = models
        val idx = _currentModelIndex.value.coerceIn(0, modelList.size - 1)
        return modelList[idx]
    }

    /**
     * Attempts to rotate to the next key. If all keys are exhausted,
     * it cascades to the next fallback model and resets key index to 0.
     *
     * @return Pair<NewApiKey, NewModel> or null if all keys and all models are exhausted.
     */
    fun rotateOnFailure(reason: String): FailoverResult? {
        if (config.apiKeys.isEmpty()) return null

        val totalKeys = config.apiKeys.size
        val nextKeyIdx = (_currentKeyIndex.value + 1) % totalKeys

        // If we cycled through all keys, cascade to the next model
        return if (nextKeyIdx == 0) {
            val totalModels = models.size
            val nextModelIdx = _currentModelIndex.value + 1
            if (nextModelIdx < totalModels) {
                _currentModelIndex.value = nextModelIdx
                _currentKeyIndex.value = 0
                FailoverResult.ModelCascaded(
                    newModel = getActiveModel(),
                    newKey = getActiveApiKey()!!,
                    keyIndex = 0,
                    reason = reason
                )
            } else {
                // All keys on all models exhausted, restart cycle from model 0 as last resort
                _currentModelIndex.value = 0
                _currentKeyIndex.value = 0
                FailoverResult.AllExhaustedRestart(
                    model = getActiveModel(),
                    key = getActiveApiKey()!!,
                    reason = reason
                )
            }
        } else {
            _currentKeyIndex.value = nextKeyIdx
            FailoverResult.KeyRotated(
                model = getActiveModel(),
                newKey = getActiveApiKey()!!,
                keyIndex = nextKeyIdx,
                reason = reason
            )
        }
    }

    fun resetModelIndex() {
        _currentModelIndex.value = 0
    }
}

sealed interface FailoverResult {
    val model: String
    val key: String
    val reason: String

    data class KeyRotated(
        override val model: String,
        val newKey: String,
        val keyIndex: Int,
        override val reason: String
    ) : FailoverResult {
        override val key: String get() = newKey
    }

    data class ModelCascaded(
        val newModel: String,
        val newKey: String,
        val keyIndex: Int,
        override val reason: String
    ) : FailoverResult {
        override val model: String get() = newModel
        override val key: String get() = newKey
    }

    data class AllExhaustedRestart(
        override val model: String,
        override val key: String,
        override val reason: String
    ) : FailoverResult
}
