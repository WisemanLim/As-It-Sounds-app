package com.asitsounds.android

import android.content.Context
import android.content.SharedPreferences
import com.asitsounds.android.whisper.TranslatorChoice
import com.asitsounds.android.whisper.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ColorSchemePref(val id: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromId(id: String?): ColorSchemePref =
            values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * User preferences backed by SharedPreferences. Avoids DataStore to keep
 * the dependency surface tiny — three keys, no async migration story.
 */
class AppSettings private constructor(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("ais.settings", Context.MODE_PRIVATE)

    private val _model = MutableStateFlow(loadModel())
    val model: StateFlow<WhisperModel> = _model.asStateFlow()

    private val _translator = MutableStateFlow(loadTranslator())
    val translator: StateFlow<TranslatorChoice> = _translator.asStateFlow()

    private val _colorScheme = MutableStateFlow(loadScheme())
    val colorScheme: StateFlow<ColorSchemePref> = _colorScheme.asStateFlow()

    fun setModel(v: WhisperModel) { prefs.edit().putString(K_MODEL, v.id).apply(); _model.value = v }
    fun setTranslator(v: TranslatorChoice) { prefs.edit().putString(K_TX, v.id).apply(); _translator.value = v }
    fun setColorScheme(v: ColorSchemePref) { prefs.edit().putString(K_SCHEME, v.id).apply(); _colorScheme.value = v }

    private fun loadModel(): WhisperModel {
        val id = prefs.getString(K_MODEL, WhisperModel.BASE.id) ?: WhisperModel.BASE.id
        return WhisperModel.values().firstOrNull { it.id == id } ?: WhisperModel.BASE
    }
    private fun loadTranslator(): TranslatorChoice {
        val id = prefs.getString(K_TX, TranslatorChoice.MYMEMORY.id) ?: TranslatorChoice.MYMEMORY.id
        return TranslatorChoice.values().firstOrNull { it.id == id } ?: TranslatorChoice.MYMEMORY
    }
    private fun loadScheme(): ColorSchemePref =
        ColorSchemePref.fromId(prefs.getString(K_SCHEME, ColorSchemePref.SYSTEM.id))

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(ctx: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(ctx).also { instance = it }
        }

        private const val K_MODEL = "model"
        private const val K_TX = "translator"
        private const val K_SCHEME = "colorScheme"
    }
}
