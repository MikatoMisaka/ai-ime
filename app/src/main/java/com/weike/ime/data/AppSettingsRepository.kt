package com.weike.ime.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore("weike_settings")

class AppSettingsRepository(private val context: Context) {
    val userModels = UserModelRepository.get(context)
    private val startupPreferences = context.applicationContext.getSharedPreferences(
        KEYBOARD_STARTUP_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val overridesKey = stringPreferencesKey("style_overrides")
    private val punctuationKey = stringPreferencesKey("punctuation_preference")
    private val hapticStrengthKey = intPreferencesKey("haptic_strength")
    private val expressionOptimizationKey = booleanPreferencesKey("expression_optimization")
    private val keyboardThemeKey = stringPreferencesKey("keyboard_theme")
    private val keyboardSoundVolumeKey = floatPreferencesKey("keyboard_sound_volume")
    private val keyboardCloseButtonKey = booleanPreferencesKey("keyboard_close_button")
    private val candidateTextSizeLevelKey = intPreferencesKey("candidate_text_size_level")
    private val englishAutoCapitalizeKey = booleanPreferencesKey("english_auto_capitalize")
    private val doubleSpacePeriodKey = booleanPreferencesKey("double_space_period")
    private val keyboardHeightLevelKey = intPreferencesKey("keyboard_height_level")
    private val keyboardBottomOffsetLevelKey = intPreferencesKey("keyboard_bottom_offset_level")
    private val punctuationShortcutsKey = booleanPreferencesKey("punctuation_shortcuts")
    private val cursorSliderEnabledKey = booleanPreferencesKey("cursor_slider_enabled")
    private val historyRetentionKey = stringPreferencesKey("history_retention")
    private val keyboardModesKey = stringPreferencesKey("keyboard_modes")
    private val chineseKeyboardLayoutKey = stringPreferencesKey("chinese_keyboard_layout")
    private val nineKeySymbolsKey = stringPreferencesKey("nine_key_symbols")
    private val keyboardStartupModeKey = stringPreferencesKey("keyboard_startup_mode")
    private val keyboardLogoStyleKey = stringPreferencesKey("keyboard_logo_style")
    private val keyboardLogoDarkPathKey = stringPreferencesKey("keyboard_logo_dark_path")
    private val keyboardLogoLightPathKey = stringPreferencesKey("keyboard_logo_light_path")
    // Legacy plaintext keys are read once, migrated into SecureSecretStore, then deleted.
    private val asrUrlKey = stringPreferencesKey("asr_api_url")
    private val asrApiKeyKey = stringPreferencesKey("asr_api_key")
    private val asrModelKey = stringPreferencesKey("asr_api_model")
    private val textUrlKey = stringPreferencesKey("text_api_url")
    private val textApiKeyKey = stringPreferencesKey("text_api_key")
    private val textModelKey = stringPreferencesKey("text_api_model")
    private val asrProviderKey = stringPreferencesKey("asr_provider")
    private val textProviderKey = stringPreferencesKey("text_provider")
    private val visionUrlKey = stringPreferencesKey("vision_api_url")
    private val visionModelKey = stringPreferencesKey("vision_api_model")
    private val visionProviderKey = stringPreferencesKey("vision_provider")
    private val replyPromptKey = stringPreferencesKey("reply_prompt")
    private val relationPromptsKey = stringPreferencesKey("relation_prompts")
    private val clipboardHistoryEnabledKey = booleanPreferencesKey("clipboard_history_enabled")
    private val recentClipboardPasteKey = booleanPreferencesKey("recent_clipboard_paste")
    private val quickImeSwitcherKey = booleanPreferencesKey("quick_ime_switcher")
    private val predictionEnabledKey = booleanPreferencesKey("prediction_enabled")
    private val predictionLearningEnabledKey = booleanPreferencesKey("prediction_learning_enabled")
    private val secrets = SecureSecretStore(context)
    private val secretMigrationMutex = Mutex()
    private var secretsMigrated = false

    val overrides = context.settingsDataStore.data.map { prefs -> decode(prefs[overridesKey].orEmpty()) }
    val punctuation = context.settingsDataStore.data.map { prefs ->
        runCatching { PunctuationPreference.valueOf(prefs[punctuationKey].orEmpty()) }.getOrDefault(PunctuationPreference.SMART)
    }
    val hapticStrength = context.settingsDataStore.data.map { prefs ->
        HapticStrength.entries.firstOrNull { it.storedValue == prefs[hapticStrengthKey] } ?: HapticStrength.MEDIUM
    }
    val expressionOptimization = context.settingsDataStore.data.map { prefs ->
        prefs[expressionOptimizationKey] ?: false
    }
    val keyboardTheme = context.settingsDataStore.data.map { prefs ->
        runCatching { KeyboardTheme.valueOf(prefs[keyboardThemeKey].orEmpty()) }.getOrDefault(KeyboardTheme.LIGHT)
    }
    val keyboardSoundVolume = context.settingsDataStore.data.map { prefs ->
        (prefs[keyboardSoundVolumeKey] ?: DEFAULT_KEYBOARD_SOUND_VOLUME).coerceIn(0f, 1f)
    }
    val keyboardCloseButtonEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[keyboardCloseButtonKey] ?: true
    }
    val candidateTextSizeLevel = context.settingsDataStore.data.map { prefs ->
        (prefs[candidateTextSizeLevelKey] ?: DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL)
            .coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
    }
    val englishAutoCapitalize = context.settingsDataStore.data.map { prefs ->
        prefs[englishAutoCapitalizeKey] ?: true
    }
    val doubleSpacePeriod = context.settingsDataStore.data.map { prefs ->
        prefs[doubleSpacePeriodKey] ?: false
    }
    val keyboardHeightLevel = context.settingsDataStore.data.map { prefs ->
        (prefs[keyboardHeightLevelKey] ?: 0).coerceIn(-2, 2)
    }
    val keyboardBottomOffsetLevel = context.settingsDataStore.data.map { prefs ->
        (prefs[keyboardBottomOffsetLevelKey] ?: 0).coerceIn(0, 4)
    }
    val punctuationShortcuts = context.settingsDataStore.data.map { prefs ->
        prefs[punctuationShortcutsKey] ?: false
    }
    val cursorSliderEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[cursorSliderEnabledKey] ?: true
    }
    val replyPrompt = context.settingsDataStore.data.map { prefs ->
        prefs[replyPromptKey]?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_REPLY_PROMPT
    }
    val relationPrompts = context.settingsDataStore.data.map { prefs ->
        decodeRelationPrompts(prefs[relationPromptsKey].orEmpty())
    }
    val historyRetention = context.settingsDataStore.data.map { prefs ->
        runCatching { HistoryRetention.valueOf(prefs[historyRetentionKey].orEmpty()) }
            .getOrDefault(HistoryRetention.NEVER)
    }
    val keyboardModes = context.settingsDataStore.data.map { prefs ->
        decodeKeyboardModes(prefs[keyboardModesKey].orEmpty())
    }
    val chineseKeyboardLayout = context.settingsDataStore.data.map { prefs ->
        runCatching { ChineseKeyboardLayout.valueOf(prefs[chineseKeyboardLayoutKey].orEmpty()) }
            .getOrDefault(ChineseKeyboardLayout.NINE_KEY)
    }
    val nineKeySymbols = context.settingsDataStore.data.map { prefs ->
        decodeNineKeySymbols(prefs[nineKeySymbolsKey].orEmpty())
    }
    val keyboardStartupMode = context.settingsDataStore.data.map { prefs ->
        runCatching { KeyboardStartupMode.valueOf(prefs[keyboardStartupModeKey].orEmpty()) }
            .getOrDefault(KeyboardStartupMode.PINYIN)
    }
    val keyboardLogo = context.settingsDataStore.data.map { prefs ->
        KeyboardLogoConfig(
            style = runCatching { KeyboardLogoStyle.valueOf(prefs[keyboardLogoStyleKey].orEmpty()) }
                .getOrDefault(KeyboardLogoStyle.VERTICK),
            darkPath = prefs[keyboardLogoDarkPathKey].orEmpty(),
            lightPath = prefs[keyboardLogoLightPathKey].orEmpty()
        )
    }
    val cloudApiSettings = flow {
        migrateCloudSecrets()
        emitAll(kotlinx.coroutines.flow.combine(context.settingsDataStore.data, userModels.changes) { prefs, _ ->
            val legacy = CloudApiSettings(
                asr = ModelEndpointConfig(
                    prefs[asrUrlKey].orEmpty(),
                    secrets.read(SECURE_ASR_KEY).orEmpty(),
                    prefs[asrModelKey].orEmpty(),
                    runCatching { CloudProvider.valueOf(prefs[asrProviderKey].orEmpty()) }
                        .getOrDefault(CloudProvider.CUSTOM)
                ),
                // 兼容旧版单模型配置；迁移后由 ModelAccessRouter 按业务选择通道。
                text = ModelEndpointConfig(
                    prefs[textUrlKey].orEmpty(),
                    secrets.read(SECURE_TEXT_KEY).orEmpty(),
                    prefs[textModelKey].orEmpty(),
                    runCatching { CloudProvider.valueOf(prefs[textProviderKey].orEmpty()) }
                        .getOrDefault(CloudProvider.CUSTOM)
                ),
                vision = ModelEndpointConfig(
                    prefs[visionUrlKey].orEmpty(), secrets.read(SECURE_VISION_KEY).orEmpty(), prefs[visionModelKey].orEmpty(),
                    runCatching { CloudProvider.valueOf(prefs[visionProviderKey].orEmpty()) }.getOrDefault(CloudProvider.CUSTOM)
                )
            )
            userModels.migrateLegacy(legacy.text, legacy.vision)
            legacy.copy(reply = userModels.endpoint(UserModelSlot.REPLY),
                superSpeak = userModels.endpoint(UserModelSlot.SUPER_SPEAK), vision = userModels.endpoint(UserModelSlot.VISION))
        })
    }
    val clipboardHistoryEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[clipboardHistoryEnabledKey] ?: false
    }
    val recentClipboardPasteEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[recentClipboardPasteKey] ?: true
    }
    val quickImeSwitcherEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[quickImeSwitcherKey] ?: false
    }
    val predictionEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[predictionEnabledKey] ?: true
    }
    val predictionLearningEnabled = context.settingsDataStore.data.map { prefs ->
        prefs[predictionLearningEnabledKey] ?: true
    }

    suspend fun styleFor(packageName: String): WritingStyle {
        return overrides.first()[packageName] ?: defaultStyleFor(packageName)
    }

    suspend fun punctuationPreference(): PunctuationPreference = punctuation.first()

    suspend fun savePunctuationPreference(preference: PunctuationPreference) {
        context.settingsDataStore.edit { prefs -> prefs[punctuationKey] = preference.name }
    }

    suspend fun hapticStrength(): HapticStrength = hapticStrength.first()

    suspend fun saveHapticStrength(strength: HapticStrength) {
        startupPreferences.edit().putInt(STARTUP_HAPTIC, strength.storedValue).apply()
        context.settingsDataStore.edit { prefs -> prefs[hapticStrengthKey] = strength.storedValue }
    }

    suspend fun expressionOptimizationEnabled(): Boolean = expressionOptimization.first()

    suspend fun saveExpressionOptimization(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[expressionOptimizationKey] = enabled }
    }

    suspend fun keyboardTheme(): KeyboardTheme = keyboardTheme.first()

    suspend fun saveKeyboardTheme(theme: KeyboardTheme) {
        startupPreferences.edit().putString(STARTUP_THEME, theme.name).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardThemeKey] = theme.name }
    }

    suspend fun keyboardSoundVolume(): Float = keyboardSoundVolume.first()

    suspend fun saveKeyboardSoundVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        startupPreferences.edit().putFloat(STARTUP_SOUND_VOLUME, normalized).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardSoundVolumeKey] = normalized }
    }

    suspend fun keyboardCloseButtonEnabled(): Boolean = keyboardCloseButtonEnabled.first()

    suspend fun saveKeyboardCloseButtonEnabled(enabled: Boolean) {
        startupPreferences.edit().putBoolean(STARTUP_CLOSE_BUTTON, enabled).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardCloseButtonKey] = enabled }
    }

    suspend fun candidateTextSizeLevel(): Int = candidateTextSizeLevel.first()

    suspend fun saveCandidateTextSizeLevel(level: Int) {
        val normalized = level.coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
        startupPreferences.edit().putInt(STARTUP_CANDIDATE_TEXT_SIZE_LEVEL, normalized).apply()
        context.settingsDataStore.edit { prefs -> prefs[candidateTextSizeLevelKey] = normalized }
    }

    suspend fun englishAutoCapitalize(): Boolean = englishAutoCapitalize.first()

    suspend fun saveEnglishAutoCapitalize(enabled: Boolean) {
        startupPreferences.edit().putBoolean(STARTUP_ENGLISH_AUTO_CAPITALIZE, enabled).apply()
        context.settingsDataStore.edit { prefs -> prefs[englishAutoCapitalizeKey] = enabled }
    }

    suspend fun doubleSpacePeriod(): Boolean = doubleSpacePeriod.first()

    suspend fun saveDoubleSpacePeriod(enabled: Boolean) {
        startupPreferences.edit().putBoolean(STARTUP_DOUBLE_SPACE_PERIOD, enabled).apply()
        context.settingsDataStore.edit { prefs -> prefs[doubleSpacePeriodKey] = enabled }
    }

    suspend fun keyboardHeightLevel(): Int = keyboardHeightLevel.first()

    suspend fun saveKeyboardHeightLevel(level: Int) {
        val normalized = level.coerceIn(-2, 2)
        startupPreferences.edit().putInt(STARTUP_HEIGHT_LEVEL, normalized).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardHeightLevelKey] = normalized }
    }

    suspend fun keyboardBottomOffsetLevel(): Int = keyboardBottomOffsetLevel.first()

    suspend fun saveKeyboardBottomOffsetLevel(level: Int) {
        val normalized = level.coerceIn(0, 4)
        startupPreferences.edit().putInt(STARTUP_BOTTOM_OFFSET_LEVEL, normalized).apply()
        context.settingsDataStore.edit { prefs -> prefs[keyboardBottomOffsetLevelKey] = normalized }
    }

    suspend fun punctuationShortcuts(): Boolean = punctuationShortcuts.first()

    suspend fun savePunctuationShortcuts(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[punctuationShortcutsKey] = enabled }
    }

    suspend fun cursorSliderEnabled(): Boolean = cursorSliderEnabled.first()

    suspend fun saveCursorSliderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[cursorSliderEnabledKey] = enabled }
    }

    suspend fun predictionEnabled(): Boolean = predictionEnabled.first()

    suspend fun savePredictionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[predictionEnabledKey] = enabled }
    }

    suspend fun predictionLearningEnabled(): Boolean = predictionLearningEnabled.first()

    suspend fun savePredictionLearningEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[predictionLearningEnabledKey] = enabled }
    }

    suspend fun historyRetention(): HistoryRetention = historyRetention.first()

    suspend fun saveHistoryRetention(retention: HistoryRetention) {
        context.settingsDataStore.edit { prefs -> prefs[historyRetentionKey] = retention.name }
    }

    suspend fun keyboardModes(): List<KeyboardModePreference> = keyboardModes.first()

    suspend fun saveKeyboardModes(modes: List<KeyboardModePreference>) {
        val normalized = modes.distinct().ifEmpty { DEFAULT_KEYBOARD_MODES }
        startupPreferences.edit().putString(STARTUP_MODES, normalized.joinToString(",") { it.name }).apply()
        context.settingsDataStore.edit { prefs ->
            prefs[keyboardModesKey] = normalized.joinToString(",") { it.name }
        }
    }

    suspend fun chineseKeyboardLayout(): ChineseKeyboardLayout = chineseKeyboardLayout.first()

    suspend fun saveChineseKeyboardLayout(layout: ChineseKeyboardLayout) {
        startupPreferences.edit().putString(STARTUP_CHINESE_LAYOUT, layout.name).apply()
        context.settingsDataStore.edit { prefs -> prefs[chineseKeyboardLayoutKey] = layout.name }
    }

    /**
     * DataStore is asynchronous. An IME may be recreated during a display
     * rotation before its first collection arrives, so keep this non-sensitive
     * rendering snapshot in SharedPreferences for synchronous startup.
     */
    fun keyboardStartupState(): KeyboardStartupState {
        val theme = startupPreferences.getString(STARTUP_THEME, null)
            ?.let { runCatching { KeyboardTheme.valueOf(it) }.getOrNull() }
            ?: KeyboardTheme.LIGHT
        val layout = startupPreferences.getString(STARTUP_CHINESE_LAYOUT, null)
            ?.let { runCatching { ChineseKeyboardLayout.valueOf(it) }.getOrNull() }
            ?: ChineseKeyboardLayout.NINE_KEY
        val modes = decodeKeyboardModes(startupPreferences.getString(STARTUP_MODES, null).orEmpty())
        val haptic = HapticStrength.entries.firstOrNull {
            it.storedValue == startupPreferences.getInt(STARTUP_HAPTIC, HapticStrength.MEDIUM.storedValue)
        } ?: HapticStrength.MEDIUM
        val volume = startupPreferences.getFloat(STARTUP_SOUND_VOLUME, DEFAULT_KEYBOARD_SOUND_VOLUME)
            .coerceIn(0f, 1f)
        val closeButtonEnabled = startupPreferences.getBoolean(STARTUP_CLOSE_BUTTON, true)
        val candidateTextSizeLevel = startupPreferences.getInt(STARTUP_CANDIDATE_TEXT_SIZE_LEVEL, DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL)
            .coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
        val englishAutoCapitalize = startupPreferences.getBoolean(STARTUP_ENGLISH_AUTO_CAPITALIZE, true)
        val doubleSpacePeriod = startupPreferences.getBoolean(STARTUP_DOUBLE_SPACE_PERIOD, false)
        val keyboardHeightLevel = startupPreferences.getInt(STARTUP_HEIGHT_LEVEL, 0).coerceIn(-2, 2)
        val keyboardBottomOffsetLevel = startupPreferences.getInt(STARTUP_BOTTOM_OFFSET_LEVEL, 0).coerceIn(0, 4)
        return KeyboardStartupState(
            theme, layout, modes, haptic, volume, closeButtonEnabled, candidateTextSizeLevel,
            englishAutoCapitalize, doubleSpacePeriod, keyboardHeightLevel, keyboardBottomOffsetLevel,
            startupPreferences.contains(STARTUP_THEME)
        )
    }

    /** A one-time upgrade bridge for an IME recreated before DataStore emits. */
    fun keyboardStartupStateBlocking(): KeyboardStartupState = runBlocking {
        val prefs = context.settingsDataStore.data.first()
        val theme = runCatching { KeyboardTheme.valueOf(prefs[keyboardThemeKey].orEmpty()) }
            .getOrDefault(KeyboardTheme.LIGHT)
        val layout = runCatching { ChineseKeyboardLayout.valueOf(prefs[chineseKeyboardLayoutKey].orEmpty()) }
            .getOrDefault(ChineseKeyboardLayout.NINE_KEY)
        val modes = decodeKeyboardModes(prefs[keyboardModesKey].orEmpty())
        val haptic = HapticStrength.entries.firstOrNull { it.storedValue == prefs[hapticStrengthKey] }
            ?: HapticStrength.MEDIUM
        val volume = (prefs[keyboardSoundVolumeKey] ?: DEFAULT_KEYBOARD_SOUND_VOLUME).coerceIn(0f, 1f)
        val closeButtonEnabled = prefs[keyboardCloseButtonKey] ?: true
        val candidateTextSizeLevel = (prefs[candidateTextSizeLevelKey] ?: DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL)
            .coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)
        val englishAutoCapitalize = prefs[englishAutoCapitalizeKey] ?: true
        val doubleSpacePeriod = prefs[doubleSpacePeriodKey] ?: false
        val keyboardHeightLevel = (prefs[keyboardHeightLevelKey] ?: 0).coerceIn(-2, 2)
        val keyboardBottomOffsetLevel = (prefs[keyboardBottomOffsetLevelKey] ?: 0).coerceIn(0, 4)
        cacheKeyboardStartupState(
            theme, layout, modes, haptic, volume, closeButtonEnabled, candidateTextSizeLevel,
            englishAutoCapitalize, doubleSpacePeriod, keyboardHeightLevel, keyboardBottomOffsetLevel
        )
        KeyboardStartupState(
            theme, layout, modes, haptic, volume, closeButtonEnabled, candidateTextSizeLevel,
            englishAutoCapitalize, doubleSpacePeriod, keyboardHeightLevel, keyboardBottomOffsetLevel, true
        )
    }

    fun cacheKeyboardStartupState(
        theme: KeyboardTheme? = null,
        layout: ChineseKeyboardLayout? = null,
        modes: List<KeyboardModePreference>? = null,
        haptic: HapticStrength? = null,
        soundVolume: Float? = null,
        closeButtonEnabled: Boolean? = null,
        candidateTextSizeLevel: Int? = null,
        englishAutoCapitalize: Boolean? = null,
        doubleSpacePeriod: Boolean? = null,
        keyboardHeightLevel: Int? = null,
        keyboardBottomOffsetLevel: Int? = null
    ) {
        startupPreferences.edit().apply {
            theme?.let { putString(STARTUP_THEME, it.name) }
            layout?.let { putString(STARTUP_CHINESE_LAYOUT, it.name) }
            modes?.let { putString(STARTUP_MODES, it.joinToString(",") { mode -> mode.name }) }
            haptic?.let { putInt(STARTUP_HAPTIC, it.storedValue) }
            soundVolume?.let { putFloat(STARTUP_SOUND_VOLUME, it.coerceIn(0f, 1f)) }
            closeButtonEnabled?.let { putBoolean(STARTUP_CLOSE_BUTTON, it) }
            candidateTextSizeLevel?.let { putInt(STARTUP_CANDIDATE_TEXT_SIZE_LEVEL, it.coerceIn(MIN_CANDIDATE_TEXT_SIZE_LEVEL, MAX_CANDIDATE_TEXT_SIZE_LEVEL)) }
            englishAutoCapitalize?.let { putBoolean(STARTUP_ENGLISH_AUTO_CAPITALIZE, it) }
            doubleSpacePeriod?.let { putBoolean(STARTUP_DOUBLE_SPACE_PERIOD, it) }
            keyboardHeightLevel?.let { putInt(STARTUP_HEIGHT_LEVEL, it.coerceIn(-2, 2)) }
            keyboardBottomOffsetLevel?.let { putInt(STARTUP_BOTTOM_OFFSET_LEVEL, it.coerceIn(0, 4)) }
        }.apply()
    }

    suspend fun nineKeySymbols(): List<String> = nineKeySymbols.first()

    suspend fun saveNineKeySymbols(symbols: List<String>) {
        val normalized = symbols.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_NINE_KEY_SYMBOLS)
            .ifEmpty { DEFAULT_NINE_KEY_SYMBOLS }
        context.settingsDataStore.edit { prefs -> prefs[nineKeySymbolsKey] = normalized.joinToString("\n") }
    }

    suspend fun keyboardStartupMode(): KeyboardStartupMode = keyboardStartupMode.first()

    suspend fun saveKeyboardStartupMode(mode: KeyboardStartupMode) {
        context.settingsDataStore.edit { prefs -> prefs[keyboardStartupModeKey] = mode.name }
    }

    suspend fun keyboardLogo(): KeyboardLogoConfig = keyboardLogo.first()

    suspend fun saveKeyboardLogo(config: KeyboardLogoConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[keyboardLogoStyleKey] = config.style.name
            prefs[keyboardLogoDarkPathKey] = config.darkPath
            prefs[keyboardLogoLightPathKey] = config.lightPath
        }
    }

    suspend fun clipboardHistoryEnabled(): Boolean = clipboardHistoryEnabled.first()

    suspend fun saveClipboardHistoryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[clipboardHistoryEnabledKey] = enabled }
    }

    suspend fun recentClipboardPasteEnabled(): Boolean = recentClipboardPasteEnabled.first()

    suspend fun saveRecentClipboardPasteEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[recentClipboardPasteKey] = enabled }
    }

    suspend fun quickImeSwitcherEnabled(): Boolean = quickImeSwitcherEnabled.first()

    suspend fun saveQuickImeSwitcherEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[quickImeSwitcherKey] = enabled }
    }

    suspend fun saveAsrApi(config: ModelEndpointConfig) {
        saveAsrApi(config, CloudProvider.CUSTOM)
    }

    suspend fun asrProvider(): CloudProvider = context.settingsDataStore.data.first().let { prefs ->
        runCatching { CloudProvider.valueOf(prefs[asrProviderKey].orEmpty()) }.getOrDefault(CloudProvider.CUSTOM)
    }

    suspend fun saveAsrApi(config: ModelEndpointConfig, provider: CloudProvider) {
        secrets.write(SECURE_ASR_KEY, config.apiKey.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[asrUrlKey] = config.url.trim()
            prefs[asrModelKey] = config.model.trim()
            prefs[asrProviderKey] = provider.name
            prefs.remove(asrApiKeyKey)
        }
    }

    suspend fun saveTextApi(config: ModelEndpointConfig) {
        saveTextApi(config, CloudProvider.CUSTOM)
    }

    suspend fun textProvider(): CloudProvider = context.settingsDataStore.data.first().let { prefs ->
        runCatching { CloudProvider.valueOf(prefs[textProviderKey].orEmpty()) }.getOrDefault(CloudProvider.CUSTOM)
    }

    suspend fun saveTextApi(config: ModelEndpointConfig, provider: CloudProvider) {
        secrets.write(SECURE_TEXT_KEY, config.apiKey.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[textUrlKey] = config.url.trim()
            prefs[textModelKey] = config.model.trim()
            prefs[textProviderKey] = provider.name
            prefs.remove(textApiKeyKey)
        }
    }

    suspend fun replyPrompt(): String = replyPrompt.first()

    suspend fun visionProvider(): CloudProvider = context.settingsDataStore.data.first().let { prefs ->
        runCatching { CloudProvider.valueOf(prefs[visionProviderKey].orEmpty()) }.getOrDefault(CloudProvider.CUSTOM)
    }

    suspend fun saveVisionApi(config: ModelEndpointConfig, provider: CloudProvider) {
        secrets.write(SECURE_VISION_KEY, config.apiKey.trim())
        context.settingsDataStore.edit { prefs ->
            prefs[visionUrlKey] = config.url.trim()
            prefs[visionModelKey] = config.model.trim()
            prefs[visionProviderKey] = provider.name
        }
    }

    suspend fun saveReplyPrompt(value: String) {
        val normalized = value.trim()
        require(normalized.length <= MAX_REPLY_PROMPT_LENGTH) { "提示词不能超过 $MAX_REPLY_PROMPT_LENGTH 个字符" }
        context.settingsDataStore.edit { prefs ->
            if (normalized.isBlank()) prefs.remove(replyPromptKey) else prefs[replyPromptKey] = normalized
        }
    }

    suspend fun relationPrompts(): Map<String, String> = relationPrompts.first()

    suspend fun saveRelationPrompt(relation: String, value: String) {
        require(relation in RELATION_TYPES) { "不支持的关系类型" }
        val normalized = value.trim()
        require(normalized.length <= MAX_REPLY_PROMPT_LENGTH) { "提示词不能超过 $MAX_REPLY_PROMPT_LENGTH 个字符" }
        context.settingsDataStore.edit { prefs ->
            val current = decodeRelationPrompts(prefs[relationPromptsKey].orEmpty()).toMutableMap()
            if (normalized.isBlank() || normalized == DEFAULT_RELATION_PROMPTS.getValue(relation)) current.remove(relation)
            else current[relation] = normalized
            prefs[relationPromptsKey] = JSONObject(current as Map<*, *>).toString()
        }
    }

    private fun decodeRelationPrompts(value: String): Map<String, String> {
        val custom = runCatching {
            val json = JSONObject(value)
            RELATION_TYPES.mapNotNull { relation ->
                json.optString(relation).trim().takeIf { it.isNotBlank() }?.let { relation to it }
            }.toMap()
        }.getOrDefault(emptyMap())
        return DEFAULT_RELATION_PROMPTS + custom
    }

    private suspend fun migrateCloudSecrets() {
        secretMigrationMutex.withLock {
            if (secretsMigrated) return
            val prefs = context.settingsDataStore.data.first()
            val legacyAsr = prefs[asrApiKeyKey].orEmpty()
            val legacyText = prefs[textApiKeyKey].orEmpty()
            if (secrets.read(SECURE_ASR_KEY).isNullOrBlank() && legacyAsr.isNotBlank()) secrets.write(SECURE_ASR_KEY, legacyAsr)
            if (secrets.read(SECURE_TEXT_KEY).isNullOrBlank() && legacyText.isNotBlank()) secrets.write(SECURE_TEXT_KEY, legacyText)
            if (legacyAsr.isNotBlank() || legacyText.isNotBlank()) {
                context.settingsDataStore.edit { updated ->
                    updated.remove(asrApiKeyKey)
                    updated.remove(textApiKeyKey)
                }
            }
            secretsMigrated = true
        }
    }

    suspend fun saveOverride(packageName: String, style: WritingStyle) {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val current = decode(prefs[overridesKey].orEmpty()).toMutableMap()
            current[cleaned] = style
            prefs[overridesKey] = encode(current)
        }
    }

    suspend fun removeOverride(packageName: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decode(prefs[overridesKey].orEmpty()).toMutableMap()
            current.remove(packageName)
            prefs[overridesKey] = encode(current)
        }
    }

    private fun defaultStyleFor(packageName: String): WritingStyle = when {
        packageName in CHAT_PACKAGES -> WritingStyle.CHAT
        packageName in OFFICE_PACKAGES -> WritingStyle.OFFICE
        packageName in NOTE_PACKAGES -> WritingStyle.NOTE
        else -> WritingStyle.CHAT
    }

    private fun decode(value: String): Map<String, WritingStyle> = value
        .split('\n')
        .mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            val style = parts.getOrNull(1)?.let { runCatching { WritingStyle.valueOf(it) }.getOrNull() }
            if (parts.size == 2 && parts[0].isNotBlank() && style != null) parts[0] to style else null
        }
        .toMap()

    private fun encode(value: Map<String, WritingStyle>): String = value.entries.joinToString("\n") {
        "${it.key}|${it.value.name}"
    }

    private fun decodeKeyboardModes(value: String): List<KeyboardModePreference> {
        val parsed = value.split(',').mapNotNull { name ->
            when (name) {
                // Upgrade the old two-button text configuration without changing user order.
                "PINYIN", "ENGLISH", "TEXT" -> KeyboardModePreference.TEXT
                else -> runCatching { KeyboardModePreference.valueOf(name) }.getOrNull()
            }
        }.distinct()
        return parsed.ifEmpty { DEFAULT_KEYBOARD_MODES }
    }

    private fun decodeNineKeySymbols(value: String): List<String> = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_NINE_KEY_SYMBOLS)
        .toList()
        .ifEmpty { DEFAULT_NINE_KEY_SYMBOLS }

    companion object {
        const val MAX_REPLY_PROMPT_LENGTH = 3000
        val RELATION_TYPES = listOf("通用", "心动对象", "恋人", "朋友", "客户", "上司", "同事", "家人")
        val DEFAULT_RELATION_PROMPTS = mapOf(
            "通用" to "把用户原话改写得自然、真诚、有分寸，保持原意和事实，不虚构信息。",
            "心动对象" to "面对心动或暧昧对象，表达要自然、有吸引力但不过度热情，保留适度分寸和聊天空间。",
            "恋人" to "面对恋人，语气亲密、真诚、有关心感，可以自然表达爱意，但不要油腻或强行承诺。",
            "朋友" to "面对朋友，语气轻松自然、平等随和，可适度幽默，不制造暧昧或上下级口吻。",
            "客户" to "面对客户，语气专业、礼貌、清晰，兼顾亲和力，不使用暧昧表达，不做未经确认的承诺。",
            "上司" to "面对上司，语气尊重、简洁、可靠，说明重点和行动，不卑不亢，不使用过度亲密表达。",
            "同事" to "面对同事，语气友好、协作、清晰，注意职场边界，不暧昧、不命令式表达。",
            "家人" to "面对家人，语气温暖、耐心、真诚，重视关心和理解，避免生硬说教或夸张表达。"
        )
        val DEFAULT_REPLY_PROMPT = """
            你是“恋爱键盘”的聊天回复助手。用户会提供聊天对象发来的原话。
            你必须站在用户本人（消息接收者）的第一人称角度，直接生成一条可以发给对方的回复，不是在回答用户的问题，也不要替对方说话。
            回复要自然、真诚、有分寸，符合恋爱或暧昧聊天语境；保持原有事实，不虚构关系、经历、时间、地点或承诺。
            只输出可直接发送的回复正文，不要分析、标题、解释、引号、角色标签或“建议回复”等前缀。
            如果对方原话信息不足，生成一条自然且不过度承诺的回应，必要时用一个简短问题延续对话。
        """.trimIndent()
        private const val KEYBOARD_STARTUP_PREFERENCES = "keyboard_startup_state"
        private const val STARTUP_THEME = "theme"
        private const val STARTUP_CHINESE_LAYOUT = "chinese_layout"
        private const val STARTUP_MODES = "modes"
        private const val STARTUP_HAPTIC = "haptic"
        private const val STARTUP_SOUND_VOLUME = "sound_volume"
        private const val STARTUP_CLOSE_BUTTON = "close_button"
        private const val STARTUP_CANDIDATE_TEXT_SIZE_LEVEL = "candidate_text_size_level"
        private const val STARTUP_ENGLISH_AUTO_CAPITALIZE = "english_auto_capitalize"
        private const val STARTUP_DOUBLE_SPACE_PERIOD = "double_space_period"
        private const val STARTUP_HEIGHT_LEVEL = "height_level"
        private const val STARTUP_BOTTOM_OFFSET_LEVEL = "bottom_offset_level"
        private const val SECURE_ASR_KEY = "asr_api_key"
        private const val SECURE_TEXT_KEY = "text_api_key"
        private const val SECURE_VISION_KEY = "vision_api_key"
        const val DEFAULT_KEYBOARD_SOUND_VOLUME = 0f
        const val MIN_CANDIDATE_TEXT_SIZE_LEVEL = -3
        const val DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL = 0
        const val MAX_CANDIDATE_TEXT_SIZE_LEVEL = 3
        const val MAX_NINE_KEY_SYMBOLS = 16
        val DEFAULT_NINE_KEY_SYMBOLS = listOf("，", "。", "？", "！", "…", "：", "、", "～")
        val DEFAULT_KEYBOARD_MODES = listOf(
            KeyboardModePreference.TEXT,
            KeyboardModePreference.ASK,
            KeyboardModePreference.VOICE
        )
        private val CHAT_PACKAGES = setOf(
            "com.tencent.mm", "com.tencent.mobileqq", "com.alibaba.android.rimet",
            "com.ss.android.lark", "com.whatsapp", "org.telegram.messenger"
        )
        private val OFFICE_PACKAGES = setOf(
            "com.microsoft.office.outlook", "com.google.android.gm", "com.microsoft.office.word",
            "com.kingsoft", "com.tencent.wework", "com.alibaba.android.rimet", "com.ss.android.lark"
        )
        private val NOTE_PACKAGES = setOf(
            "com.miui.notes", "com.xiaomi.notes", "com.youdao.note", "com.evernote", "notion.id"
        )
    }
}

data class KeyboardStartupState(
    val theme: KeyboardTheme,
    val chineseLayout: ChineseKeyboardLayout,
    val modes: List<KeyboardModePreference>,
    val haptic: HapticStrength,
    val soundVolume: Float,
    val closeButtonEnabled: Boolean,
    val candidateTextSizeLevel: Int,
    val englishAutoCapitalize: Boolean,
    val doubleSpacePeriod: Boolean,
    val keyboardHeightLevel: Int,
    val keyboardBottomOffsetLevel: Int,
    val isSeeded: Boolean
)
