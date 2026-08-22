package com.livetranslate.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationMode(val displayName: String, val description: String) {
    SOLO("Односторонний", "Захват речи оппонента и перевод строго в наушники"),
    DIALOGUE("Двусторонний", "Синхронный диалог между двумя языками через динамик или гарнитуру")
}

@Serializable
enum class VoiceName(val apiName: String, val genderDescription: String) {
    PUCK("Puck", "Мужской (нейтральный)"),
    CHARON("Charon", "Мужской (глубокий)"),
    AOEDE("Aoede", "Женский (мягкий)"),
    FENRIR("Fenrir", "Мужской (энергичный)"),
    KORE("Kore", "Женский (спокойный)")
}

@Serializable
data class Language(
    val code: String,
    val name: String,
    val nativeName: String
) {
    companion object {
        val SUPPORTED_LANGUAGES = listOf(
            Language("ru", "Русский", "Русский"),
            Language("en", "Английский", "English"),
            Language("kk", "Казахский", "Қазақша"),
            Language("zh", "Китайский", "中文"),
            Language("es", "Испанский", "Español"),
            Language("de", "Немецкий", "Deutsch"),
            Language("fr", "Французский", "Français"),
            Language("it", "Итальянский", "Italiano"),
            Language("tr", "Турецкий", "Türkçe"),
            Language("ar", "Арабский", "العربية"),
            Language("ja", "Японский", "日本語"),
            Language("ko", "Корейский", "한국어")
        )

        fun findByCode(code: String): Language {
            return SUPPORTED_LANGUAGES.find { it.code.equals(code, ignoreCase = true) }
                ?: Language(code, code, code)
        }
    }
}
