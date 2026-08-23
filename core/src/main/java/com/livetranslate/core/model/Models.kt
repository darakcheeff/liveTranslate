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
    val nativeName: String,
    val englishName: String = name
) {
    companion object {
        val SUPPORTED_LANGUAGES = listOf(
            Language("ru", "Русский", "Русский", "Russian"),
            Language("en", "Английский", "English", "English"),
            Language("kk", "Казахский", "Қазақша", "Kazakh"),
            Language("zh", "Китайский", "中文", "Chinese"),
            Language("es", "Испанский", "Español", "Spanish"),
            Language("de", "Немецкий", "Deutsch", "German"),
            Language("fr", "Французский", "Français", "French"),
            Language("it", "Итальянский", "Italiano", "Italian"),
            Language("tr", "Турецкий", "Türkçe", "Turkish"),
            Language("ar", "Арабский", "العربية", "Arabic"),
            Language("ja", "Японский", "日本語", "Japanese"),
            Language("ko", "Корейский", "한국어", "Korean")
        )

        fun findByCode(code: String): Language {
            return SUPPORTED_LANGUAGES.find { it.code.equals(code, ignoreCase = true) }
                ?: Language(code, code, code, code)
        }
    }
}
