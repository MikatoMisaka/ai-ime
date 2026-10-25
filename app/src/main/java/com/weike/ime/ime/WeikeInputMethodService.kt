package com.weike.ime.ime

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.icu.text.BreakIterator
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.weike.ime.R
import com.weike.ime.ai.AiChatActivity
import com.weike.ime.ai.AiInsertBridge
import com.weike.ime.data.AppContainer
import com.weike.ime.data.AppSettingsRepository
import com.weike.ime.data.ChineseKeyboardLayout
import com.weike.ime.data.ClipboardEntry
import com.weike.ime.data.CloudApiSettings
import com.weike.ime.data.CloudProvider
import com.weike.ime.data.DictionaryPackManager
import com.weike.ime.data.HapticStrength
import com.weike.ime.data.HistoryRetention
import com.weike.ime.data.InputHistory
import com.weike.ime.data.InputHistoryType
import com.weike.ime.data.KeyboardTheme
import com.weike.ime.data.KeyboardModePreference
import com.weike.ime.data.KeyboardStartupMode
import com.weike.ime.data.KeyboardLogoConfig
import com.weike.ime.data.PunctuationPreference
import com.weike.ime.data.PinyinLearning
import com.weike.ime.data.PredictionLearning
import com.weike.ime.data.TypingDictionaryEntry
import com.weike.ime.data.VoiceUiState
import com.weike.ime.data.WritingStyle
import com.weike.ime.network.MimoTextPolisher
import com.weike.ime.network.TextPolisher
import com.weike.ime.network.ModelAccessRouter
import com.weike.ime.network.ModelFeature
import com.weike.ime.network.ModelSetupRequiredException
import com.weike.ime.network.RoutedTextPolisher
import com.weike.ime.network.VisionReplyClient
import com.weike.ime.speech.AudioRecorder
import com.weike.ime.speech.BoundedPcmBuffer
import com.weike.ime.speech.AsrClient
import com.weike.ime.speech.RoutedAsrClient
import com.weike.ime.text.JiebaSegmenter
import com.weike.ime.text.StructuredExpressionFormatter
import com.weike.ime.text.DraftEditor
import com.weike.ime.workflow.WorkflowIntent
import com.weike.ime.workflow.WorkflowReplaceTarget
import com.weike.ime.workflow.WorkflowRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class WeikeInputMethodService : InputMethodService(), KeyboardActions {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private lateinit var recorder: AudioRecorder
    private val visionReplyClient by lazy { VisionReplyClient() }
    @Volatile private var screenshotReplyEnabled = false
    private var visionJob: Job? = null
    private lateinit var keyboard: WeikeKeyboardView
    private var inputViewHost: FrameLayout? = null
    private var overlayHost: FrameLayout? = null
    private var overlayWindowManager: WindowManager? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var overlayContext: Context? = null
    private var overlayPanel: FloatingKeyboardPanel? = null
    private lateinit var pinyinDecoder: PinyinDecoder
    private lateinit var jieba: JiebaSegmenter
    private lateinit var predictionEngine: PredictionEngine
    private lateinit var keyboardSound: KeyboardSoundPlayer
    private lateinit var clipboardManager: ClipboardManager
    @Volatile private var cloudApiSettings = CloudApiSettings()
    private var replyPrompt = AppSettingsRepository.DEFAULT_REPLY_PROMPT
    private var polishRelation = "通用"
    private var relationPrompts = AppSettingsRepository.DEFAULT_RELATION_PROMPTS
    private var polishDraft = ""
    private var polishCursor = 0
    private var polishOptions: List<String> = emptyList()
    private var polishLoading = false
    private var replySource = ""
    private var replyOptions: List<String> = emptyList()
    private var replyLoading = false
    private var replyEmotion = ""
    private val polisher: TextPolisher by lazy { RoutedTextPolisher(modelAccess) { endpoint ->
        MimoTextPolisher(endpointProvider = { endpoint }, replyPromptProvider = { replyPrompt },
            polishRelationProvider = { polishRelation }, relationPromptsProvider = { relationPrompts })
    } }
    private val modelAccess by lazy { ModelAccessRouter({ cloudApiSettings }) }
    private val customAsrClient = RoutedAsrClient(endpointProvider = { cloudApiSettings.asr })
    private val asrClient = object : AsrClient {
        override suspend fun transcribe(pcm: ByteArray): Result<String> = customAsrClient.transcribe(pcm)
        override suspend fun testConnection(): Result<Unit> = customAsrClient.testConnection()
    }

    /** 旧版会拉取账号/VIP 配置；现在功能固定可用，这里只保留占位。 */
    private fun refreshAppConfiguration() {
        screenshotReplyEnabled = true
    }

    private var mode = KeyboardMode.PINYIN
    private var modeBeforeSymbols = KeyboardMode.PINYIN
    private var visibleKeyboardModes = listOf(KeyboardMode.VOICE, KeyboardMode.TEXT)
    private var voiceState: VoiceUiState = VoiceUiState.Idle
    private var style = WritingStyle.CHAT
    private var punctuation = PunctuationPreference.SMART
    private var hapticStrength = HapticStrength.MEDIUM
    private var keyboardSoundVolume = AppSettingsRepository.DEFAULT_KEYBOARD_SOUND_VOLUME
    private var keyboardCloseButtonEnabled = true
    private var quickImeSwitcherEnabled = false
    private var keyboardStartupMode = KeyboardStartupMode.PINYIN
    private var keyboardLogo = KeyboardLogoConfig()
    private var candidateTextSizeLevel = AppSettingsRepository.DEFAULT_CANDIDATE_TEXT_SIZE_LEVEL
    private var englishAutoCapitalize = true
    private var doubleSpacePeriod = false
    // The nine-key "，。?!" key commits a comma up front and opens the strip.
    // If the user then picks a different mark, that leading comma was a
    // placeholder and must be replaced, not accumulated ("你好，？" -> "你好？").
    private var autoPunctuationPending = false
    private var autoPunctuationAtMs = 0L
    private var autoPunctuationText = ""
    private var keyboardTheme = KeyboardTheme.DARK
    private var keyboardThemePreference = KeyboardTheme.DARK
    private var expressionOptimization = false
    private var chineseKeyboardLayout = ChineseKeyboardLayout.FULL
    private var nineKeySymbols = AppSettingsRepository.DEFAULT_NINE_KEY_SYMBOLS
    private var pinyinBuffer = ""
    // Unlike pinyinBuffer this keeps the actual decoder code. They differ for T9:
    // the editor displays "ni" while the decoder must retain "64".
    private var pinyinRawBuffer = ""
    // AI 失败引导卡："reply|<路由层差异化的错误说明>"；null = 不显示。
    private var pinyinCandidates: List<PinyinCandidate> = emptyList()
    private var spellingOptions: List<String> = emptyList()
    private var selectedSpelling = ""
    private var predictionCandidates: List<PredictionCandidate> = emptyList()
    private var predictionEnabled = true
    private var predictionLearningEnabled = true
    private var predictionRequestId = 0L
    private var predictionContextTokens: List<String> = emptyList()
    private val pinyinMutex = Mutex()
    private val languageEngineMutex = Mutex()
    private val pinyinOperations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val predictionOperations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var pinyinReady = false
    private var engineLoadJob: Job? = null
    private var engineIdleReleaseJob: Job? = null
    private var rimeTermStageJob: Job? = null
    private var pendingPinyinMutations = 0
    private val pendingPinyinLetters = StringBuilder()
    private var pinyinBatchScheduled = false
    private var lastSyncedTerms: List<com.weike.ime.data.LexiconTerm> = emptyList()
    private var lastSyncedTypingDictionary: List<TypingDictionaryEntry> = emptyList()
    private var lastPinyinLearning: List<PinyinLearning> = emptyList()
    private var englishBuffer = ""
    private var englishCandidates: List<PinyinCandidate> = emptyList()
    private var lastTextSpaceAtMs = 0L
    private var lastTextSpaceConnection: InputConnection? = null
    private var clipboardEntries: List<ClipboardEntry> = emptyList()
    private var lastClipboardContent = ""
    private var clipboardHistoryEnabled = false
    private var recentClipboardPasteEnabled = true
    private var recentClipboardContent = ""
    private var recentClipboardCapturedAtMs = 0L
    private var recentClipboardVisibleUntilMs = 0L
    private var inputViewActive = false
    private var keyboardHeightLevel = 0
    private var keyboardBottomOffsetLevel = 0
    private var punctuationShortcuts = false
    private var cursorSliderEnabled = true
    private var sensitiveField = false
    private var rawTranscript = ""
    private var finishVoiceJob: Job? = null
    private var dictationActive = false
    private var dictationArmed = false
    private var dictationPartial = ""
    private var dictationBuffer = BoundedPcmBuffer(MAX_PCM_BYTES)
    private var dictationJob: Job? = null
    private var dictationSessionId = 0L
    private var commitTextJob: Job? = null
    private var audioBuffer = BoundedPcmBuffer(MAX_PCM_BYTES)
    private var voiceSessionId = 0L
    private var activeVoiceMode: KeyboardMode? = null
    private var activeVoicePolish = false
    private var activeVoiceTranslation = false
    private var lastCommittedText: String? = null
    private var lastCommitConnection: InputConnection? = null
    private var lastPackageName: String? = null
    private var voiceStartedAtMs = 0L
    private var activeRecordingDurationMs = 0L
    private var editorSessionId = 0L
    private var inputViewRecovery: InputViewRecovery? = null
    private var configurationChangeUntilMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var renderPending = false
    private val renderFrameCallback = Choreographer.FrameCallback {
        renderPending = false
        if (::keyboard.isInitialized) {
            keyboard.update(
                mode, voiceState, style, pinyinBuffer, pinyinCandidates,
                englishBuffer, englishCandidates, predictionCandidates, rawTranscript, sensitiveField, hapticStrength,
                pinyinReady, pinyinDecoder.statusText, keyboardTheme, keyboardSoundVolume, visibleKeyboardModes,
                clipboardEntries, chineseKeyboardLayout == ChineseKeyboardLayout.NINE_KEY,
                modeBeforeSymbols == KeyboardMode.ENGLISH, nineKeySymbols, keyboardCloseButtonEnabled,
                quickImeSwitcherEnabled, candidateTextSizeLevel, englishAutoCapitalize, shouldAutoCapitalizeEnglish(), keyboardLogo,
                currentRecentClipboard(), keyboardHeightLevel, keyboardBottomOffsetLevel, punctuationShortcuts,
                cursorSliderEnabled, polishDraft, polishCursor, polishOptions, polishLoading,
                replyOptions, replyLoading, replyEmotion, enterActionLabel(), dictationActive, screenshotReplyEnabled,
                spellingOptions, selectedSpelling
            )
        }
    }
    private val clearLearningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                ACTION_CLEAR_RIME_LEARNING -> clearPinyinLearning()
                ACTION_CLEAR_PREDICTION_LEARNING -> clearPredictionLearning()
                ACTION_RELOAD_RIME_BUNDLE -> reloadPinyinBundle()
                ACTION_IMAGE_PICKED -> {
                    val uri = intent.data ?: pendingImageUri
                    pendingImageUri = null
                    uri?.let(::handlePickedImage)
                }
                AiInsertBridge.ACTION_AI_INSERT -> commitPendingAiText()
            }
        }
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { capturePrimaryClipboard() }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        var startup = container.settings.keyboardStartupState()
        // A cache is populated after the first settings observation. When an app
        // upgrades from an older version, synchronously seed it once so a service
        // recreated by rotation does not flash the default dark palette.
        if (!startup.isSeeded) startup = container.settings.keyboardStartupStateBlocking()
        keyboardThemePreference = startup.theme
        keyboardTheme = resolveKeyboardTheme(startup.theme)
        chineseKeyboardLayout = ChineseKeyboardLayout.FULL
        // This build ships a plain keyboard plus the AI panel only: the old
        // "帮你回 / 超会说 / 剪贴板" modes are intentionally not exposed.
        visibleKeyboardModes = listOf(KeyboardMode.TEXT)
        hapticStrength = startup.haptic
        // 按键音默认关闭：旧版缓存里可能还留着 0.45，先按 0 启动，
        // 真实设置由下面的 DataStore collector 覆盖。
        keyboardSoundVolume = 0f
        keyboardCloseButtonEnabled = startup.closeButtonEnabled
        candidateTextSizeLevel = startup.candidateTextSizeLevel
        englishAutoCapitalize = startup.englishAutoCapitalize
        doubleSpacePeriod = startup.doubleSpacePeriod
        keyboardHeightLevel = startup.keyboardHeightLevel
        keyboardBottomOffsetLevel = startup.keyboardBottomOffsetLevel
        if (!isConfiguredMode(mode) && mode != KeyboardMode.SYMBOLS) {
            mode = initialModeFor(visibleKeyboardModes.firstOrNull() ?: KeyboardMode.TEXT)
        }
        recorder = AudioRecorder(this)
        keyboardSound = KeyboardSoundPlayer(this)
        pinyinDecoder = RimePinyinDecoder(this)
        // Only the 26-key layout ships now. The Rime schema must be switched
        // too: leaving it on the T9 schema makes letter keys produce nothing.
        enqueuePinyin {
            pinyinDecoder.selectChineseKeyboardLayout(ChineseKeyboardLayout.FULL)
            applyPinyinState(pinyinDecoder.clear(), allowCommit = false)
        }
        serviceScope.launch(Dispatchers.IO) {
            runCatching { container.settings.saveChineseKeyboardLayout(ChineseKeyboardLayout.FULL) }
        }
        jieba = JiebaSegmenter(this)
        predictionEngine = PredictionEngine(this, jieba)
        clipboardManager = getSystemService(ClipboardManager::class.java)
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        capturePrimaryClipboard()
        ContextCompat.registerReceiver(
            this,
            clearLearningReceiver,
            IntentFilter().apply {
                addAction(ACTION_CLEAR_RIME_LEARNING)
                addAction(ACTION_CLEAR_PREDICTION_LEARNING)
                addAction(ACTION_RELOAD_RIME_BUNDLE)
                addAction(ACTION_IMAGE_PICKED)
                addAction(AiInsertBridge.ACTION_AI_INSERT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        serviceScope.launch {
            for (operation in pinyinOperations) {
                try {
                    pinyinMutex.withLock { operation() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // A malformed dictionary record or a one-off InputConnection
                    // failure must never kill the serialized key-event consumer.
                    // Once that coroutine exits, every following nine-key press is
                    // accepted by the view but remains forever queued.
                    Log.e(TAG, "Pinyin operation failed; resetting composition", error)
                    pendingPinyinMutations = 0
                    runCatching {
                        pinyinMutex.withLock {
                            applyPinyinState(pinyinDecoder.clear(), allowCommit = false)
                        }
                    }.onFailure { resetError ->
                        Log.e(TAG, "Unable to recover Pinyin operation queue", resetError)
                    }
                }
            }
        }
        serviceScope.launch {
            for (operation in predictionOperations) {
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Prediction operation failed", error)
                }
            }
        }
        serviceScope.launch {
            container.settings.hapticStrength.collect { strength ->
                hapticStrength = strength
                container.settings.cacheKeyboardStartupState(haptic = strength)
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardSoundVolume.collect { volume ->
                keyboardSoundVolume = volume
                container.settings.cacheKeyboardStartupState(soundVolume = volume)
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardCloseButtonEnabled.collect { enabled ->
                keyboardCloseButtonEnabled = enabled
                container.settings.cacheKeyboardStartupState(closeButtonEnabled = enabled)
                render()
            }
        }
        serviceScope.launch {
            container.settings.candidateTextSizeLevel.collect { level ->
                candidateTextSizeLevel = level
                container.settings.cacheKeyboardStartupState(candidateTextSizeLevel = level)
                render()
            }
        }
        serviceScope.launch {
            container.settings.englishAutoCapitalize.collect { enabled ->
                englishAutoCapitalize = enabled
                container.settings.cacheKeyboardStartupState(englishAutoCapitalize = enabled)
                render()
            }
        }
        serviceScope.launch {
            container.settings.doubleSpacePeriod.collect { enabled ->
                doubleSpacePeriod = enabled
                container.settings.cacheKeyboardStartupState(doubleSpacePeriod = enabled)
            }
        }
        serviceScope.launch {
            container.settings.keyboardHeightLevel.collect { level ->
                keyboardHeightLevel = level
                container.settings.cacheKeyboardStartupState(keyboardHeightLevel = level)
                if (::keyboard.isInitialized) keyboard.requestLayout()
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardBottomOffsetLevel.collect { level ->
                keyboardBottomOffsetLevel = level
                container.settings.cacheKeyboardStartupState(keyboardBottomOffsetLevel = level)
                if (::keyboard.isInitialized) keyboard.requestLayout()
                render()
            }
        }
        serviceScope.launch {
            container.settings.punctuationShortcuts.collect { enabled ->
                punctuationShortcuts = enabled
                render()
            }
        }
        serviceScope.launch {
            container.settings.cursorSliderEnabled.collect { enabled ->
                cursorSliderEnabled = enabled
                if (::keyboard.isInitialized) keyboard.requestLayout()
                render()
            }
        }
        serviceScope.launch {
            container.settings.predictionEnabled.collect { enabled ->
                predictionEnabled = enabled
                if (!enabled) clearPredictions()
            }
        }
        serviceScope.launch {
            container.settings.predictionLearningEnabled.collect { enabled ->
                predictionLearningEnabled = enabled
            }
        }
        serviceScope.launch {
            container.settings.keyboardTheme.collect { theme ->
                keyboardThemePreference = theme
                keyboardTheme = resolveKeyboardTheme(theme)
                container.settings.cacheKeyboardStartupState(theme = theme)
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardModes.collect { configured ->
                visibleKeyboardModes = listOf(KeyboardMode.TEXT)
                if (!isConfiguredMode(mode) && mode != KeyboardMode.SYMBOLS) {
                    if (voiceState != VoiceUiState.Idle || activeVoiceMode != null) cancelActiveVoiceSession()
                    mode = initialModeFor(visibleKeyboardModes.first())
                }
                container.settings.cacheKeyboardStartupState(modes = listOf(KeyboardModePreference.TEXT))
                render()
            }
        }
        serviceScope.launch {
            container.settings.keyboardStartupMode.collect { startupMode ->
                keyboardStartupMode = startupMode
            }
        }
        serviceScope.launch {
            container.settings.keyboardLogo.collect { logo ->
                keyboardLogo = logo
                render()
            }
        }
        serviceScope.launch {
            container.settings.chineseKeyboardLayout.collect {
                // The nine-key layout was removed from this build; always use
                // the full 26-key keyboard and its matching Rime schema.
                chineseKeyboardLayout = ChineseKeyboardLayout.FULL
                container.settings.cacheKeyboardStartupState(layout = ChineseKeyboardLayout.FULL)
                enqueuePinyin {
                    pinyinDecoder.selectChineseKeyboardLayout(ChineseKeyboardLayout.FULL)
                    applyPinyinState(pinyinDecoder.clear(), allowCommit = false)
                }
                render()
            }
        }
        serviceScope.launch {
            container.settings.nineKeySymbols.collect { symbols ->
                nineKeySymbols = symbols
                render()
            }
        }
        serviceScope.launch {
            container.settings.quickImeSwitcherEnabled.collect { enabled ->
                quickImeSwitcherEnabled = enabled
                render()
            }
        }
        serviceScope.launch {
            container.settings.cloudApiSettings.collect { settings ->
                cloudApiSettings = settings
            }
        }
        refreshAppConfiguration()
        serviceScope.launch { container.settings.replyPrompt.collect { replyPrompt = it } }
        serviceScope.launch { container.settings.relationPrompts.collect { relationPrompts = it } }
        serviceScope.launch {
            container.settings.historyRetention.collect { retention ->
                pruneHistory(retention)
            }
        }
        serviceScope.launch {
            lastSyncedTerms = container.lexicon.all()
            lastSyncedTypingDictionary = container.typingDictionary.all()
            lastPinyinLearning = container.pinyinLearning.all()
            EnglishCandidateEngine.initialize(this@WeikeInputMethodService, container.englishLearning.all(), lastSyncedTypingDictionary)
            requestPinyinEngine()
            container.lexicon.observeAll().collect { terms ->
                if (terms == lastSyncedTerms) return@collect
                lastSyncedTerms = terms
                if (jieba.isReady) {
                    serviceScope.launch(Dispatchers.Default) {
                        languageEngineMutex.withLock { jieba.syncProfessionalTerms(terms) }
                    }
                }
                deferRimeTermSync()
            }
        }
        serviceScope.launch {
            container.typingDictionary.observeAll().collect { entries ->
                if (entries == lastSyncedTypingDictionary) return@collect
                lastSyncedTypingDictionary = entries
                EnglishCandidateEngine.syncTypingDictionary(entries)
                deferRimeTermSync()
            }
        }
        serviceScope.launch {
            container.clipboard.observeRecent().collect { entries ->
                clipboardEntries = entries
                render()
            }
        }
        serviceScope.launch {
            container.settings.clipboardHistoryEnabled.collect { enabled ->
                clipboardHistoryEnabled = enabled
                if (!enabled) {
                    lastClipboardContent = ""
                    container.clipboard.deleteAll()
                } else {
                    capturePrimaryClipboard()
                }
            }
        }
        serviceScope.launch {
            container.settings.recentClipboardPasteEnabled.collect { enabled ->
                recentClipboardPasteEnabled = enabled
                if (!enabled) clearRecentClipboard()
                else capturePrimaryClipboard()
                render()
            }
        }
    }

    override fun onCreateInputView(): FrameLayout {
        Log.d(TAG, "onCreateInputView; mode=$mode; pinyin=${pinyinRawBuffer.length}; english=${englishBuffer.length}")
        val host = FrameLayout(this)
        inputViewHost = host
        ensureKeyboard()
        if (!isLandscapeOverlayActive()) attachKeyboardTo(host)
        render()
        return host
    }

    private fun ensureKeyboard() {
        if (!::keyboard.isInitialized) keyboard = WeikeKeyboardView(this, this, keyboardSound)
    }

    private fun attachKeyboardTo(host: ViewGroup) {
        (keyboard.parent as? ViewGroup)?.removeView(keyboard)
        if (keyboard.parent == null) {
            host.addView(
                keyboard,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun isLandscapeOverlayActive(): Boolean =
        overlayHost != null && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun updateLandscapeOverlay() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            dismissLandscapeOverlay()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先在恋爱键盘的权限管理中授权悬浮窗权限")
            return
        }
        if (overlayHost != null) return
        ensureKeyboard()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        // Landscape floating keyboard: width : height = 1.7 : 1. Its height
        // is capped at 60% of the available screen, then width is derived from
        // that cap so the requested ratio never changes.
        val maxPanelHeight = minOf(dp(330), (screenHeight * .60f).toInt())
        val panelHeight = minOf(maxPanelHeight, ((screenWidth - dp(32)) / 1.7f).toInt())
        val panelWidth = (panelHeight * 1.7f).toInt()
        val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            // API 29 has no window contexts; the service context still accepts
            // application-overlay windows there.
            this
        }
        val panel = FloatingKeyboardPanel(
            context = windowContext,
            canDrag = { pinyinBuffer.isBlank() && englishBuffer.isBlank() },
            showCloseButton = keyboardCloseButtonEnabled,
            onClose = ::closeLandscapeKeyboard,
            onMove = ::moveLandscapeKeyboard
        )
        panel.alpha = .80f
        attachKeyboardTo(panel.keyboardHost)
        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = (screenWidth - panelWidth) / 2
            y = (screenHeight - panelHeight) / 2
            title = "Vertick Landscape Keyboard"
        }
        runCatching {
            val manager = windowContext.getSystemService(WindowManager::class.java)
            manager.addView(panel, params)
            overlayHost = panel
            overlayPanel = panel
            overlayWindowManager = manager
            overlayLayoutParams = params
            overlayContext = windowContext
            Log.d(TAG, "Landscape overlay attached")
            // Keep only the overlay at the top level. The standard IME window
            // would otherwise reserve bottom insets and push the editor up.
            mainHandler.post { requestHideSelf(0) }
        }.onFailure { error ->
            Log.e(TAG, "Unable to attach landscape overlay", error)
            inputViewHost?.let(::attachKeyboardTo)
        }
    }

    private fun scheduleLandscapeOverlay(delayMs: Long = 0L) {
        // Do not post this work to `keyboard`: after a floating keyboard is
        // closed that view has no attached window, and View.post() may never
        // run. The service main looper remains valid across both IME windows.
        mainHandler.removeCallbacks(landscapeOverlayRunnable)
        mainHandler.postDelayed(landscapeOverlayRunnable, delayMs)
    }

    private val landscapeOverlayRunnable = Runnable { updateLandscapeOverlay() }

    private fun dismissLandscapeOverlay() {
        val host = overlayHost ?: return
        runCatching { overlayWindowManager?.removeViewImmediate(host) }
            .onFailure { Log.w(TAG, "Unable to remove landscape overlay", it) }
        overlayHost = null
        overlayPanel = null
        overlayWindowManager = null
        overlayLayoutParams = null
        overlayContext = null
    }

    private fun moveLandscapeKeyboard(x: Int, y: Int) {
        val host = overlayHost ?: return
        val params = overlayLayoutParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        params.x = x.coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = y.coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
        runCatching { overlayWindowManager?.updateViewLayout(host, params) }
            .onFailure { Log.w(TAG, "Unable to move landscape overlay", it) }
    }

    private fun closeLandscapeKeyboard() {
        dismissLandscapeOverlay()
        // Reset the framework's IME session as well. The next focused text
        // field produces onShowInputRequested(), where we reattach the overlay.
        requestHideSelf(0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private inner class FloatingKeyboardPanel(
        context: Context,
        private val canDrag: () -> Boolean,
        private val showCloseButton: Boolean,
        private val onClose: () -> Unit,
        private val onMove: (Int, Int) -> Unit
    ) : FrameLayout(context) {
        val keyboardHost = FrameLayout(context)
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var panelStartX = 0f
        private var panelStartY = 0f
        private var dragging = false

        init {
            setWillNotDraw(false)
            clipToOutline = false
            addView(keyboardHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            if (showCloseButton) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_lucide_chevron_down)
                    // Match the unselected icons in the keyboard's mode capsule.
                    setColorFilter(
                        if (keyboardTheme == KeyboardTheme.DARK) {
                            android.graphics.Color.rgb(190, 190, 192)
                        } else {
                            android.graphics.Color.rgb(105, 105, 112)
                        }
                    )
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(19).toFloat()
                        setColor(
                            if (keyboardTheme == KeyboardTheme.DARK) {
                                android.graphics.Color.rgb(44, 44, 46)
                            } else {
                                android.graphics.Color.rgb(235, 235, 239)
                            }
                        )
                    }
                    contentDescription = "Hide floating keyboard"
                    setOnClickListener { onClose() }
                }, LayoutParams(dp(38), dp(38), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                    topMargin = dp(10)
                    marginEnd = dp(7)
                })
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Candidate rows replace the brand bar. Never intercept their first
            // entries as a drag gesture.
            val dragHandle = canDrag() && event.x <= dp(156) && event.y <= dp(58)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = dragHandle
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    panelStartX = overlayLayoutParams?.x?.toFloat() ?: 0f
                    panelStartY = overlayLayoutParams?.y?.toFloat() ?: 0f
                    return dragging
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        onMove(
                            (panelStartX + event.rawX - dragStartX).toInt(),
                            (panelStartY + event.rawY - dragStartY).toInt()
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return true
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val dragHandle = canDrag() && event.x <= dp(156) && event.y <= dp(58)
                    dragHandle
                }
                else -> dragging
            }
        }
    }

    /**
     * The framework normally enters its legacy extract-editor UI in landscape.
     * Vertick only owns a custom input view, not an extract view; allowing that
     * transition replaces the keyboard with the framework's initial state and
     * loses the active editor on HyperOS. Keep the regular IME surface in both
     * orientations without changing its visual layout.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val isPlaceholderConnection = (attribute?.fieldId ?: 0) < 0
        val recovery = inputViewRecovery?.takeIf {
            !isPlaceholderConnection &&
                it.matches(attribute, isSensitive(attribute)) &&
                SystemClock.elapsedRealtime() <= it.expiresAtMs
        }
        val configurationRestart = recovery != null ||
            (restarting && SystemClock.elapsedRealtime() <= configurationChangeUntilMs)
        Log.d(
            TAG,
            "onStartInput; restarting=$restarting; package=${attribute?.packageName}; field=${attribute?.fieldId}; " +
                "recovery=${recovery != null}; mode=$mode"
        )
        inputViewRecovery = null
        if (voiceState != VoiceUiState.Idle || activeVoiceMode != null || rawTranscript.isNotBlank()) {
            cancelActiveVoiceSession()
        }
        if (dictationActive) stopDictation(commit = false)
        lastPackageName = attribute?.packageName
        sensitiveField = isSensitive(attribute)
        if (!configurationRestart) {
            editorSessionId += 1
            currentInputConnection?.finishComposingText()
            clearTextCompositions()
            clearPredictions(clearContext = true)
            if (sensitiveField) mode = KeyboardMode.ENGLISH
            else applyConfiguredStartupMode()
            // 选图返回后键盘按启动模式重置了：无论回调与广播谁先到，都恢复到帮你回
            if (pendingAskRestore && !sensitiveField && mode != KeyboardMode.ASK) {
                applyMode(KeyboardMode.ASK)
            }
        } else if (recovery != null) {
            restoreInputViewRecovery(recovery)
            Log.d(TAG, "Restored IME session after configuration change; mode=${recovery.mode}")
        } else {
            // The client connection was recreated but Android did not send a
            // finish callback. Preserve the existing decoder state rather than
            // treating rotation as a new editor session.
            Log.d(TAG, "Preserved IME session for configuration restart")
        }
        serviceScope.launch {
            style = if (sensitiveField) WritingStyle.RAW else container.settings.styleFor(attribute?.packageName.orEmpty())
            punctuation = container.settings.punctuationPreference()
            hapticStrength = container.settings.hapticStrength()
            keyboardSoundVolume = container.settings.keyboardSoundVolume()
            expressionOptimization = container.settings.expressionOptimizationEnabled()
            render()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        Log.d(TAG, "onStartInputView; restarting=$restarting; mode=$mode; pinyin=${pinyinRawBuffer.length}; english=${englishBuffer.length}")
        super.onStartInputView(info, restarting)
        inputViewActive = true
        applyChatKeyboardPreference(info)
        refreshAppConfiguration()
        autoPunctuationPending = false
        if (pendingAskRestore) {
            pendingAskRestore = false
            if (!sensitiveField && mode != KeyboardMode.ASK) applyMode(KeyboardMode.ASK)
        }
        presentRecentClipboardForInputView()
        // A reopened keyboard must land on the letter grid, never on the emoji
        // panel or a stale punctuation strip left over from the last session.
        if (::keyboard.isInitialized) keyboard.resetTransientPanels()
        // Some Xiaomi builds recreate only the input view. Reassert the composing
        // value against the newly supplied InputConnection in that case.
        if (pinyinBuffer.isNotBlank()) updateComposingText(pinyinBuffer)
        else if (englishBuffer.isNotBlank()) updateComposingText(englishBuffer)
        if (::keyboard.isInitialized) keyboard.requestLayout()
        render()
        if (AiInsertBridge.hasPending()) {
            // 全屏对话页盖住宿主时连接可能已失效；等键盘重新显示再插入，
            // 保证文本落回用户原输入框。
            mainHandler.postDelayed({ commitPendingAiText() }, 120L)
        }
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        val accepted = super.onShowInputRequested(flags, configChange)
        if (!configChange && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Only a fresh request from the focused editor may open the floating
            // keyboard. Rotation, shade expansion and window recreation also
            // reach the IME lifecycle, but must never summon an overlay.
            ensureKeyboard()
            scheduleLandscapeOverlay(80L)
        }
        return accepted
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // The IME supplies its own switcher and dismissal controls. Hide only
        // Android's IME caption buttons, preserving system gesture navigation.
        window?.window?.decorView?.let { decor ->
            // Caption-bar insets control exists from API 30; older ROMs draw no
            // caption bar on IME windows anyway.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                decor.windowInsetsController?.hide(WindowInsets.Type.captionBar())
                decor.post {
                    decor.windowInsetsController?.hide(WindowInsets.Type.captionBar())
                }
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.d(TAG, "onFinishInputView; finishingInput=$finishingInput; mode=$mode; pinyin=${pinyinRawBuffer.length}; english=${englishBuffer.length}")
        restoreChatKeyboardPreference()
        // Xiaomi may report `finishingInput=true` while replacing the view during
        // rotation. Keep a short-lived, same-editor snapshot in either case; a
        // different field cannot restore it because InputViewRecovery.matches()
        // checks the package, input type, and field id.
        captureInputViewRecovery()
        inputViewActive = false
        if (dictationActive) stopDictation(commit = true)
        stopVoice(cancelled = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // Some Xiaomi rotation paths finish the editor before they finish the
        // input view. Capture here too; the snapshot is accepted only by the
        // same package/input type/field during the five-second rotation window.
        captureInputViewRecovery()
        Log.d(TAG, "onFinishInput; mode=$mode; pinyin=${pinyinRawBuffer.length}")
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        // HyperOS hides and recreates the regular IME window during landscape
        // rotation. That is precisely when the overlay takes over, so treating
        // this callback as an explicit user dismissal creates an endless
        // show/hide loop. Portrait uses the system IME normally and still
        // clears any stale fallback surface.
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.d(TAG, "onWindowHidden; mode=$mode; landscape=$landscape; overlay=${overlayHost != null}")
        if (!landscape) dismissLandscapeOverlay()
        inputViewActive = false
        super.onWindowHidden()
    }

    override fun onUnbindInput() {
        // Back gestures and editor changes can unbind the IME without sending a
        // separate hide callback on HyperOS. The landscape surface must never
        // outlive that input session.
        dismissLandscapeOverlay()
        super.onUnbindInput()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        captureInputViewRecovery()
        configurationChangeUntilMs = SystemClock.elapsedRealtime() + INPUT_VIEW_RECOVERY_WINDOW_MS
        Log.d(TAG, "onConfigurationChanged; orientation=${newConfig.orientation}; mode=$mode; pinyin=${pinyinRawBuffer.length}")
        super.onConfigurationChanged(newConfig)
        if (keyboardThemePreference == KeyboardTheme.SYSTEM) {
            keyboardTheme = resolveKeyboardTheme(keyboardThemePreference)
            render()
        }
        if (::keyboard.isInitialized) keyboard.requestLayout()
        // The mapped dictionary is already alive. Reopening it on every rotation
        // races with composition restore and previously cleared the nine-key code.
        // A cold service still initializes through onCreate().
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            // The overlay is only a landscape fallback. Never leave it attached
            // above the app after the user returns to portrait.
            dismissLandscapeOverlay()
        } else {
            // A delayed task posted before rotation would otherwise create an
            // overlay even though the user has not focused an editor in the
            // new orientation.
            mainHandler.removeCallbacks(landscapeOverlayRunnable)
        }
        render()
    }

    override fun onDestroy() {
        visionJob?.cancel()
        stopDictation(commit = false)
        stopVoice(cancelled = true)
        mainHandler.removeCallbacks(landscapeOverlayRunnable)
        dismissLandscapeOverlay()
        if (::keyboardSound.isInitialized) keyboardSound.release()
        engineLoadJob?.cancel()
        engineIdleReleaseJob?.cancel()
        rimeTermStageJob?.cancel()
        runBlocking(Dispatchers.Default) {
            languageEngineMutex.withLock {
                pinyinDecoder.terminate()
                jieba.release()
            }
        }
        if (::clipboardManager.isInitialized) clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        unregisterReceiver(clearLearningReceiver)
        pinyinOperations.close()
        predictionOperations.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun selectMode(mode: KeyboardMode) {
        if (mode == KeyboardMode.TEXT) {
            toggleTextKeyboard()
            return
        }
        if (sensitiveField && (mode == KeyboardMode.VOICE || mode == KeyboardMode.ASK)) return
        if (this.mode == mode) return
        if (this.mode != mode && (voiceState != VoiceUiState.Idle || activeVoiceMode != null || rawTranscript.isNotBlank())) {
            cancelActiveVoiceSession()
        }
        if (dictationActive) stopDictation(commit = true)
        flushPendingPinyinBatch()
        if (this.mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueuePinyin {
                commitCurrentPinyin()
                applyMode(mode)
            }
        } else {
            if (this.mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) {
                // Commit unfinished letters before leaving ENGLISH, otherwise the
                // composing region is dropped by the next commitText and the
                // typed letters silently vanish.
                val carried = englishBuffer
                englishBuffer = ""
                englishCandidates = emptyList()
                updateComposingText("")
                commitTextAndPredict(carried)
            }
            applyMode(mode)
        }
    }

    override fun returnToNineKeyPinyin() {
        if (sensitiveField) return
        // Nine-key input is gone: route the old shortcut to 26-key Pinyin.
        chineseKeyboardLayout = ChineseKeyboardLayout.FULL
        finishCompositionThen { applyMode(KeyboardMode.PINYIN) }
        enqueuePinyin {
            runCatching {
                pinyinDecoder.selectChineseKeyboardLayout(ChineseKeyboardLayout.FULL)
            }
        }
    }

    override fun closeKeyboard() {
        if (dictationActive) stopDictation(commit = true)
        if (voiceState != VoiceUiState.Idle || activeVoiceMode != null || rawTranscript.isNotBlank()) {
            cancelActiveVoiceSession()
        }
        if (isLandscapeOverlayActive()) dismissLandscapeOverlay()
        requestHideSelf(0)
    }

    private fun applyMode(nextMode: KeyboardMode) {
        val previous = mode
        mode = nextMode
        if (previous != nextMode) clearPredictions()
        rememberKeyboardMode(mode)
        if (mode == KeyboardMode.PINYIN) requestPinyinEngine()
        if (mode == KeyboardMode.ASK || mode == KeyboardMode.VOICE) refreshAppConfiguration()
        if (previous == KeyboardMode.PINYIN && mode != KeyboardMode.PINYIN) scheduleLanguageEngineRelease()
        render()
    }

    private fun requestPinyinEngine() {
        engineIdleReleaseJob?.cancel()
        if (engineLoadJob?.isActive == true) return
        pinyinReady = pinyinDecoder.isReady
        engineLoadJob = serviceScope.launch(Dispatchers.Default) {
            runCatching {
                ensureLatestLanguageData()
                pinyinReady = pinyinDecoder.startSession(lastSyncedTerms, lastSyncedTypingDictionary, lastPinyinLearning)
                pinyinDecoder.selectChineseKeyboardLayout(chineseKeyboardLayout)
                ensureJiebaReady()
            }.onFailure {
                pinyinReady = false
                Log.e(TAG, "Unable to start librime Pinyin", it)
            }
            render()
        }
    }

    private suspend fun ensureJiebaReady(): Boolean {
        engineIdleReleaseJob?.cancel()
        return languageEngineMutex.withLock {
            ensureLatestLanguageData()
            ensureJiebaReadyLocked()
        }
    }

    private suspend fun ensureJiebaReadyLocked(): Boolean {
        if (jieba.isReady) return true
        return jieba.initialize(lastSyncedTerms)
    }

    private suspend fun ensureLatestLanguageData() {
        if (lastSyncedTerms.isEmpty()) lastSyncedTerms = container.lexicon.all()
        if (lastSyncedTypingDictionary.isEmpty()) lastSyncedTypingDictionary = container.typingDictionary.all()
        if (lastPinyinLearning.isEmpty()) lastPinyinLearning = container.pinyinLearning.all()
    }

    private fun scheduleLanguageEngineRelease() {
        // Keeping Rime warm prevents the loading state whenever the IME is reopened.
        // Resources are released only when the IME service is destroyed.
        engineIdleReleaseJob?.cancel()
    }

    private fun deferRimeTermSync() {
        // Updating a user dictionary only updates an in-memory overlay. It must not
        // restart or compile an engine while the user is typing.
        rimeTermStageJob?.cancel()
        rimeTermStageJob = serviceScope.launch(Dispatchers.Default) {
            runCatching {
                pinyinDecoder.syncProfessionalTerms(lastSyncedTerms, lastSyncedTypingDictionary)
            }.onFailure { Log.e(TAG, "Unable to update local Pinyin terms", it) }
        }
    }

    private fun toggleTextKeyboard() {
        val next = if (mode == KeyboardMode.PINYIN) KeyboardMode.ENGLISH else KeyboardMode.PINYIN
        finishCompositionThen { applyMode(next) }
    }

    override fun toggleVoice() {
        Log.d(TAG, "toggleVoice; state=$voiceState")
        when (voiceState) {
            VoiceUiState.Idle, is VoiceUiState.Error, is VoiceUiState.Preview -> startVoice(polish = false)
            VoiceUiState.Listening -> stopVoice(cancelled = false)
            VoiceUiState.Processing -> Unit
        }
    }

    override fun startPolishedVoice() {
        if (mode != KeyboardMode.VOICE) return
        when (voiceState) {
            VoiceUiState.Idle, is VoiceUiState.Error, is VoiceUiState.Preview -> startVoice(polish = true)
            else -> Unit
        }
    }

    override fun setLongPressTranslation(selected: Boolean) {
        if (voiceState == VoiceUiState.Listening && activeVoiceMode == KeyboardMode.VOICE && activeVoicePolish) {
            activeVoiceTranslation = selected
        }
    }

    override fun finishVoice() {
        if (voiceState == VoiceUiState.Listening) stopVoice(cancelled = false)
    }

    override fun cancelVoice() = stopVoice(cancelled = true)

    /**
     * 键盘上的随身听写：麦克风按钮或长按空格触发。录音期间每 1.4 秒把最近
     * 15 秒音频送去识别并作为 composing 文本实时上屏；结束时用完整音频做
     * 终次识别并正式提交。
     */
    override fun toggleDictation() {
        if (dictationActive) stopDictation(commit = true) else startDictation()
    }

    /** 录音权限缺失时只提示用户去系统设置，旧版授权页已移除。 */
    private fun openMicPermissionSetup() {
        toast("请在系统设置里授予本输入法麦克风权限")
    }

    override fun startDictation() {
        if (dictationActive) return
        if (sensitiveField) {
            toast("敏感输入框不能使用语音输入")
            return
        }
        if (voiceState != VoiceUiState.Idle || activeVoiceMode != null) {
            toast("正在使用超会说，请先结束当前语音")
            return
        }
        if (!recorder.canRecord()) {
            toast("未授予麦克风权限，正在打开恋爱键盘授权")
            openMicPermissionSetup()
            return
        }
        // finishCompositionThen 可能排队等拼音组合提交完；armed 保证排队期间
        // 用户松开（finishDictation）后，迟到的启动会被丢弃，不会出现
        // 「松手后录音才开始且停不下来」。
        dictationArmed = true
        finishCompositionThen { startDictationInternal() }
    }

    override fun finishDictation() {
        dictationArmed = false
        if (dictationActive) stopDictation(commit = true)
    }

    private fun startDictationInternal() {
        if (!dictationArmed) return
        dictationArmed = false
        if (!cloudApiSettings.asr.isComplete()) {
            toast("请先在设置里配置语音接口")
            render()
            return
        }
        dictationActive = true
        dictationPartial = ""
        dictationBuffer.clear()
        dictationSessionId += 1
        val sessionId = dictationSessionId
        clearPredictions()
        recorder.start(
            serviceScope,
            { chunk, count ->
                val keepGoing = dictationBuffer.append(chunk, count)
                if (!keepGoing) {
                    serviceScope.launch {
                        if (dictationActive) stopDictation(commit = true)
                    }
                }
                keepGoing
            },
            { level -> if (::keyboard.isInitialized) keyboard.post { keyboard.setAudioLevel(level) } }
        ) { error ->
            serviceScope.launch {
                if (dictationActive) {
                    stopDictation(commit = false)
                    toast(error)
                }
            }
        }
        dictationJob = serviceScope.launch {
            while (dictationActive && sessionId == dictationSessionId) {
                delay(DICTATION_PARTIAL_INTERVAL_MS)
                if (!dictationActive || sessionId != dictationSessionId) break
                // 用完整音频做中间识别：内容只增不减，避免滑窗截断造成
                // 「显示一大段又缩回几个字」的闪退观感。
                val pcm = dictationBuffer.tail(Int.MAX_VALUE)
                if (pcm.size < AudioRecorder.SAMPLE_RATE) continue
                val partial = asrClient.transcribe(pcm).getOrNull()?.trim().orEmpty()
                if (partial.isNotBlank() && dictationActive && sessionId == dictationSessionId) {
                    dictationPartial = partial
                    currentInputConnection?.setComposingText(partial, 1)
                }
            }
        }
        render()
    }

    private fun stopDictation(commit: Boolean) {
        if (!dictationActive) return
        dictationActive = false
        dictationSessionId += 1
        dictationJob?.cancel()
        dictationJob = null
        recorder.stop()
        val pcm = dictationBuffer.snapshotAndClear().pcm
        val connection = currentInputConnection
        render()
        if (!commit || connection == null || pcm.isEmpty() || sensitiveField) {
            if (commit) runCatching {
                connection?.setComposingText(dictationPartial, 1)
                connection?.finishComposingText()
            }
            return
        }
        serviceScope.launch {
            // 终次识别失败（网络抖动等）时回退到已实时上屏的中间结果，
            // 不能把用户看到的文字清掉。
            val final = asrClient.transcribe(pcm).getOrNull()?.trim().orEmpty()
                .ifBlank { dictationPartial }
            if (final.isBlank()) {
                runCatching {
                    connection.setComposingText("", 1)
                    connection.finishComposingText()
                }
                toastFor("没有识别到内容，请重试", 1_500L)
                return@launch
            }
            val batchStarted = connection.beginBatchEdit()
            try {
                if (connection.setComposingText(final, 1)) connection.finishComposingText()
                else connection.commitText(final, 1)
            } finally {
                if (batchStarted) connection.endBatchEdit()
            }
            lastCommittedText = final
            lastCommitConnection = connection
            schedulePredictions(final, connection)
        }
    }

    override fun dismissAnswer() {
        if (mode == KeyboardMode.ASK && voiceState is VoiceUiState.Preview) {
            rawTranscript = ""
            voiceState = VoiceUiState.Idle
            render()
        }
    }

    override fun insertAnswer() {
        val answer = (voiceState as? VoiceUiState.Preview)?.takeIf {
            mode == KeyboardMode.ASK && !it.streaming && it.text.isNotBlank()
        } ?: return
        if (sensitiveField) {
            toast("敏感输入框不能插入问答内容")
            return
        }
        finishCompositionThen {
            val connection = currentInputConnection ?: return@finishCompositionThen
            connection.commitText(answer.text, 1)
            lastCommittedText = answer.text
            lastCommitConnection = connection
            rawTranscript = ""
            voiceState = VoiceUiState.Idle
            render()
        }
    }

    override fun pasteClipboard(entry: ClipboardEntry) {
        finishCompositionThen { currentInputConnection?.commitText(entry.content, 1) }
    }

    override fun pasteRecentClipboard() {
        val content = recentClipboardContent.takeIf {
            it.isNotBlank() && SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs <= RECENT_CLIPBOARD_WINDOW_MS
        } ?: return
        finishCompositionThen { currentInputConnection?.commitText(content, 1) }
        clearRecentClipboard()
        render()
    }

    /** 模型缺失时只给一句提示；旧版配置/订阅弹窗已删除。 */
    private fun showModelSetupDialog(message: String) {
        toast(message)
    }

    private fun withModelAccess(feature: ModelFeature, action: () -> Unit) {
        serviceScope.launch {
            try {
                modelAccess.route(feature)
                if (!sensitiveField) action()
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) {
                voiceState = VoiceUiState.Error(error.message.orEmpty())
                if (error is ModelSetupRequiredException) showModelSetupDialog(error.message.orEmpty())
                render()
            }
        }
    }

    /** 截图识别回复：校验模型通道 → 相册选聊天截图 → 视觉模型 → 多条场景化回复。 */
    override fun screenshotReply() {
        if (sensitiveField || mode != KeyboardMode.ASK) return
        serviceScope.launch {
            try {
                modelAccess.route(ModelFeature.VISION)
                // 输入法无法直接弹相册，经应用内选图页中转（一键截屏与本应用
                // 任务切换打架会截到自己，已废弃）。
                startActivity(
                    android.content.Intent(this@WeikeInputMethodService, com.weike.ime.ImagePickerActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) {
                voiceState = VoiceUiState.Error(error.message ?: "无法打开相册")
                if (error is ModelSetupRequiredException) showModelSetupDialog(error.message.orEmpty())
                render()
            }
        }
    }

    private var lastVisionUri: android.net.Uri? = null

    /** 选图后键盘重新显示时要恢复到帮你回（onStartInput 会按启动模式重置，需在 view 显示时再切回）。 */
    @Volatile
    private var pendingAskRestore = false

    /** 图片解码专用线程：媒体提供器偶发阻塞时靠 Future 超时兜底，线程本身可能滞留但 UI 不受影响。 */
    private val imageDecodeExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vision-image-decode").apply { isDaemon = true }
        }
    }

    private fun handlePickedImage(uri: android.net.Uri) {
        if (sensitiveField) return
        // 广播与选图页关闭后的 onStartInput/onStartInputView 回调之间没有固定顺序：
        // 标记必须先于一切早退设置，两处生命周期回调里都会按它恢复帮你回。
        pendingAskRestore = true
        // 全屏选图会经历键盘隐藏/重建，回来时可能已被重置回拼音模式：主动切回帮你回
        if (mode != KeyboardMode.ASK) applyMode(KeyboardMode.ASK)
        lastVisionUri = uri
        visionJob?.cancel()
        replySource = ""
        replyOptions = emptyList()
        replyEmotion = "识别截图中…"
        replyLoading = true
        voiceState = VoiceUiState.Idle
        render()
        // 全屏选图页关闭后输入法窗口处于收起状态，主动拉起键盘展示识别进度
        runCatching { requestShowSelf(0) }
        visionJob = serviceScope.launch {
            val route = try { modelAccess.route(ModelFeature.VISION) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    replyLoading = false
                    voiceState = VoiceUiState.Error(error.message.orEmpty())
                    if (error is ModelSetupRequiredException) showModelSetupDialog(error.message.orEmpty())
                    render()
                    return@launch
                }
            // 个别 ROM 的媒体提供器会偶发阻塞 openInputStream（协程取消不了阻塞 IO），
            // 用带超时的 Future 兜底：卡住也能在限时后恢复 UI 并提示，而不是永远转圈。
            val image = runCatching {
                imageDecodeExecutor.submit(java.util.concurrent.Callable { downscaleAndEncode(uri) })
                    .get(12, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrNull()
            if (image == null) {
                replyLoading = false
                voiceState = VoiceUiState.Error("读取图片超时，请重新选择")
                render()
                return@launch
            }
            val (base64, mime) = image
            (if (route.official) visionReplyClient.reply(route.endpoint.apiKey, base64, mime)
             else visionReplyClient.replyWithModel(route.endpoint, base64, mime))
                .onSuccess { result ->
                    replyLoading = false
                    replyOptions = result.replies
                    replyEmotion = buildString {
                    append(result.scenario.ifBlank { "其他" })
                        if (result.analysis.isNotBlank()) append(" · ").append(result.analysis)
                    }
                    // 等待期间模式可能又被重置，结果落地时确保停在帮你回
                    if (mode != KeyboardMode.ASK) applyMode(KeyboardMode.ASK)
                    render()
                }
                .onFailure { error ->
                    replyLoading = false
                    voiceState = VoiceUiState.Error(error.message ?: "识别失败，请稍后重试")
                    if (mode != KeyboardMode.ASK) applyMode(KeyboardMode.ASK)
                    render()
                }
        }
    }

    /** 读取相册图片，最长边压到 1280、JPEG 80，避免超出请求体限制。 */
    private fun downscaleAndEncode(uri: android.net.Uri): Pair<String, String>? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)
        bitmap.recycle()
        android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP) to "image/jpeg"
    }.getOrNull()

    override fun analyzeClipboardReply() {
        if (sensitiveField || mode != KeyboardMode.ASK) return
        withModelAccess(ModelFeature.REPLY) {
            if (mode != KeyboardMode.ASK) return@withModelAccess
            val content = runCatching {
                clipboardManager.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(this)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            }.getOrDefault("")
            if (content.isBlank()) {
                voiceState = VoiceUiState.Error("剪贴板没有文字，请先复制TA的话")
                render()
                return@withModelAccess
            }
            replySource = content
            generateReplyOptions()
        }
    }

    override fun regenerateReplyOptions() {
        if (mode != KeyboardMode.ASK) return
        // 剪贴板分析的结果走文本重新生成；截图识别的结果重新识别同一张图
        if (replySource.isNotBlank()) generateReplyOptions()
        else lastVisionUri?.let { handlePickedImage(it) }
    }

    override fun selectReplyOption(text: String) {
        if (mode != KeyboardMode.ASK || text.isBlank()) return
        replaceCurrentFieldWithAiOption(text)
        render()
    }

    override fun dismissReplyOptions() {
        replyOptions = emptyList()
        replyLoading = false
        replyEmotion = ""
        voiceState = VoiceUiState.Idle
        render()
    }

    private fun generateReplyOptions() {
        val source = replySource.trim()
        if (source.isBlank()) return
        replyLoading = true
        replyOptions = emptyList()
        replyEmotion = when {
            Regex("难过|伤心|生气|失望|讨厌|不开心").containsMatchIn(source) -> "需要安慰"
            Regex("吗|呢|一起|想请|有空|期待").containsMatchIn(source) -> "开心期待"
            else -> "友好交流"
        }
        voiceState = VoiceUiState.Idle
        render()
        serviceScope.launch {
            polisher.suggestReplies(source).onSuccess {
                replyLoading = false
                replyOptions = it
                render()
            }.onFailure { error ->
                replyLoading = false
                Log.w(TAG, "Reply suggestions unavailable; showing setup guide", error)
                // 未配置类（ModelSetupRequiredException）由 withModelAccess 弹出
                // 「选择 AI 使用方式」弹窗；其余错误用画布引导卡提示。
                if (error is ModelSetupRequiredException) {
                    showModelSetupDialog(error.message.orEmpty())
                } else {
                    replyEmotion = error.message ?: "生成失败"
                }
                replyOptions = emptyList()
                replyEmotion = ""
                voiceState = VoiceUiState.Idle
                render()
            }
        }
    }

    override fun setPolishRelation(relation: String) {
        polishRelation = relation.takeIf { it in setOf("通用", "心动对象", "恋人", "朋友", "客户", "上司", "同事", "家人") } ?: "通用"
    }

    override fun setPolishCursor(index: Int) {
        if (mode != KeyboardMode.VOICE || pinyinRawBuffer.isNotBlank()) return
        polishCursor = index.coerceIn(0, polishDraft.length)
        render()
    }

    override fun clearComposition() {
        flushPendingPinyinBatch()
        if (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0) {
            enqueueAfterPendingPinyin {
                applyPinyinState(pinyinDecoder.clear(), allowCommit = false)
            }
        } else if (englishBuffer.isNotBlank()) {
            englishBuffer = ""
            englishCandidates = emptyList()
            updateComposingText("")
            render()
        }
    }

    override fun polishCurrentText() {
        if (sensitiveField || mode != KeyboardMode.VOICE) return
        withModelAccess(ModelFeature.SUPER_SPEAK) {
            if (mode != KeyboardMode.VOICE) return@withModelAccess
            flushPendingPinyinBatch()
            val source = polishDraft.trim()
            if (source.isBlank()) {
                voiceState = VoiceUiState.Error("请先输入想要优化的话")
                render()
                return@withModelAccess
            }
            polishLoading = true
            polishOptions = emptyList()
            render()
            serviceScope.launch {
                polisher.suggestPolishes(source, polishRelation).onSuccess { options ->
                    polishLoading = false
                    polishOptions = options
                    voiceState = VoiceUiState.Idle
                    render()
                }.onFailure { error ->
                    polishLoading = false
                    Log.w(TAG, "Polish suggestions unavailable; showing setup guide", error)
                    if (error is ModelSetupRequiredException) {
                        showModelSetupDialog(error.message.orEmpty())
                    } else {
                        voiceState = VoiceUiState.Error(error.message.orEmpty())
                    }
                    polishOptions = emptyList()
                    voiceState = VoiceUiState.Idle
                    render()
                }
            }
        }
    }

    override fun selectPolishOption(text: String) {
        if (mode != KeyboardMode.VOICE || text.isBlank()) return
        replaceCurrentFieldWithAiOption(text)
        render()
    }

    override fun dismissPolishOptions() {
        polishOptions = emptyList()
        polishLoading = false
        render()
    }

    /**
     * Choosing another generated option is a replacement, not another append.
     * The candidate panel remains open until its explicit close action.
     */
    private fun replaceCurrentFieldWithAiOption(replacement: String) {
        val connection = currentInputConnection ?: return
        // Prefer replacing only the span this keyboard inserted (a prior option
        // or dictation); pre-existing text typed by the user must survive.
        val trackedLength = lastCommittedText
            ?.takeIf { lastCommitConnection === connection }
            ?.length ?: 0
        // 不能用 Int.MAX_VALUE 取全文：个别 ROM 的本地 BaseInputConnection 会因
        // 「光标位置 + 长度」整型溢出申请 4GB 内存直接 OOM 闪退。聊天输入框远小于 2048。
        val before = connection.getTextBeforeCursor(2048, 0)?.length ?: 0
        val after = connection.getTextAfterCursor(2048, 0)?.length ?: 0
        val deleteBefore = if (trackedLength in 1..before) trackedLength else before
        val deleteAfter = if (trackedLength > 0) 0 else after
        runCatching {
            if (deleteBefore > 0 || deleteAfter > 0) connection.deleteSurroundingText(deleteBefore, deleteAfter)
            connection.commitText(replacement, 1)
        }.onSuccess {
            lastCommittedText = replacement
            lastCommitConnection = connection
        }
    }

    override fun deleteClipboard(entry: ClipboardEntry) {
        if (lastClipboardContent == entry.content) lastClipboardContent = ""
        serviceScope.launch(Dispatchers.IO) { container.clipboard.delete(entry.id) }
    }

    private fun startVoice(polish: Boolean) {
        if (sensitiveField) {
            mode = KeyboardMode.ENGLISH
            render()
            return
        }
        if (!recorder.canRecord()) {
            voiceState = VoiceUiState.Error("未授予麦克风权限，正在打开恋爱键盘授权")
            toast("未授予麦克风权限，正在打开恋爱键盘授权")
            openMicPermissionSetup()
            render()
            return
        }
        finishVoiceJob?.cancel()
        finishVoiceJob = null
        val sessionMode = if (mode == KeyboardMode.ASK) KeyboardMode.ASK else KeyboardMode.VOICE
        voiceSessionId += 1
        val sessionId = voiceSessionId
        activeVoiceMode = sessionMode
        activeVoicePolish = polish && sessionMode == KeyboardMode.VOICE
        activeVoiceTranslation = false
        rawTranscript = ""
        audioBuffer = BoundedPcmBuffer(MAX_PCM_BYTES)
        voiceStartedAtMs = System.currentTimeMillis()
        activeRecordingDurationMs = 0L
        lastCommittedText = null
        commitTextJob?.cancel()
        voiceState = VoiceUiState.Listening
        recorder.start(serviceScope, { chunk, count -> audioBuffer.append(chunk, count) }, { level ->
            if (::keyboard.isInitialized) keyboard.post { keyboard.setAudioLevel(level) }
        }) { error ->
            serviceScope.launch {
                if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
                recorder.stop()
                activeVoiceMode = null
                voiceState = VoiceUiState.Error(error)
                render()
            }
        }
        render()
    }

    private fun stopVoice(cancelled: Boolean) {
        val recordingDurationMs = if (voiceStartedAtMs == 0L) 0 else System.currentTimeMillis() - voiceStartedAtMs
        activeRecordingDurationMs = recordingDurationMs
        val sessionId = voiceSessionId
        val sessionMode = activeVoiceMode ?: if (mode == KeyboardMode.ASK) KeyboardMode.ASK else KeyboardMode.VOICE
        val askMode = sessionMode == KeyboardMode.ASK
        val polishMode = activeVoicePolish
        val translationMode = activeVoiceTranslation
        Log.d(TAG, "stopVoice; cancelled=$cancelled; durationMs=$recordingDurationMs; state=$voiceState; sessionMode=$sessionMode")
        finishVoiceJob?.cancel()
        finishVoiceJob = null
        recorder.stop()
        voiceStartedAtMs = 0L
        if (cancelled) {
            cancelActiveVoiceSession()
            render()
            return
        }
        val minimumDurationMs = if (polishMode || translationMode) MINIMUM_POLISHED_RECORDING_MS else MINIMUM_RECORDING_MS
        if (recordingDurationMs < minimumDurationMs) {
            voiceSessionId += 1
            activeVoiceMode = null
            activeVoicePolish = false
            activeVoiceTranslation = false
            rawTranscript = ""
            audioBuffer.clear()
            voiceState = VoiceUiState.Error("录音时间过短，请说完一句后再点完成")
            render()
            return
        }
        voiceState = VoiceUiState.Processing
        render()
        finishVoiceJob = serviceScope.launch {
            ensureVoiceSession(sessionId, sessionMode)
            val capture = audioBuffer.snapshotAndClear()
            if (capture.truncated) {
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("录音时间过长，请缩短到 ${AudioRecorder.MAX_RECORDING_SECONDS} 秒以内")
                render()
                return@launch
            }
            val pcm = capture.pcm
            if (pcm.isEmpty()) {
                if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("没有采集到语音，请重试")
                render()
                return@launch
            }
            val activePunctuation = if (askMode) punctuation else container.settings.punctuationPreference()
            val transcription = withTimeoutOrNull(ASR_TIMEOUT_MS) {
                ensureVoiceSession(sessionId, sessionMode)
                asrClient.transcribe(pcm)
            } ?: Result.failure(IllegalStateException("语音识别超时，请重试"))
            ensureVoiceSession(sessionId, sessionMode)
            val source = transcription.getOrElse { error ->
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("语音识别失败：${error.message ?: "网络不可用"}")
                render()
                return@launch
            }.trim()
            if (source.isBlank()) {
                activeVoiceMode = null
                voiceState = VoiceUiState.Error("没有识别到语音，请重试")
                render()
                return@launch
            }
            rawTranscript = source
            if (!askMode && !polishMode) {
                commitResult(
                    applyPunctuationPreference(source, activePunctuation),
                    sessionId,
                    sessionMode,
                    instant = true,
                    historyType = InputHistoryType.DICTATION,
                    recordingDurationMs = recordingDurationMs
                )
                return@launch
            }
            try {
                modelAccess.route(if (askMode) ModelFeature.REPLY else ModelFeature.SUPER_SPEAK)
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) {
                activeVoiceMode = null
                voiceState = VoiceUiState.Error(error.message.orEmpty())
                if (error is ModelSetupRequiredException) showModelSetupDialog(error.message.orEmpty())
                render()
                return@launch
            }
            if (translationMode) {
                val translated = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                    ensureVoiceSession(sessionId, sessionMode)
                    polisher.translateToAmericanEnglish(source)
                }?.getOrElse { error ->
                    Log.w(TAG, "Translation failed; inserting transcription", error)
                    source
                } ?: source
                commitResult(
                    translated,
                    sessionId,
                    sessionMode,
                    instant = true,
                    historyType = InputHistoryType.TRANSLATION,
                    recordingDurationMs = recordingDurationMs
                )
                return@launch
            }
            val allTerms = if (askMode) emptyList() else container.lexicon.all()
            if (!askMode) ensureJiebaReady()
            val segmentedTerms = if (askMode) emptySet() else jieba.segment(source, com.weike.ime.text.JiebaMode.SEARCH)
                .filter { it.professional }
                .map { it.text }
                .toSet()
            val terms = allTerms.sortedBy { if (it.term in segmentedTerms) 0 else 1 }
            val optimizeExpression = !askMode && container.settings.expressionOptimizationEnabled()
            val explicitStructureHint = StructuredExpressionFormatter.needsStructure(source)
            val structureHint = !askMode && (
                explicitStructureHint || (optimizeExpression && jieba.hasStructuredExpression(source))
            )
            val deepPolish = !askMode && (explicitStructureHint || (optimizeExpression && shouldUseDeepPolish(source)))
            val processingTimeout = when {
                askMode -> ANSWER_PROCESSING_TIMEOUT_MS
                deepPolish -> DEEP_POLISHING_TIMEOUT_MS
                else -> FAST_POLISHING_TIMEOUT_MS
            }
            val result = withTimeoutOrNull(processingTimeout) {
                ensureVoiceSession(sessionId, sessionMode)
                // Both voice modes always send ASR text, never PCM audio, to the text model.
                if (askMode) return@withTimeoutOrNull processAskWorkflow(source, sessionId, sessionMode)
                if (!deepPolish) {
                    val fastText = polisher.polishFast(source, style, activePunctuation, terms).getOrElse { error ->
                        Log.w(TAG, "Fast polishing failed; inserting local fallback", error)
                        localPolishFallback(source)
                    }
                    val structuredFastText = StructuredExpressionFormatter.enforce(source, fastText)
                    return@withTimeoutOrNull VoiceProcessingResult.Success(
                        text = applyPunctuationPreference(structuredFastText, activePunctuation),
                        instant = true
                    )
                }
                val polishedText = try {
                    val polished = StringBuilder()
                    var receivedPolishingDelta = false
                    polisher.polishStream(source, style, activePunctuation, terms, optimizeExpression, structureHint).collect { delta ->
                        ensureVoiceSession(sessionId, sessionMode)
                        polished.append(delta)
                        if (::keyboard.isInitialized) {
                            if (!receivedPolishingDelta) keyboard.setPolishingStreamActive(true)
                            keyboard.notePolishingDelta()
                        }
                        receivedPolishingDelta = true
                    }
                    polished.toString().trim().ifBlank { source }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Polishing stream failed; falling back to standard request", error)
                    if (::keyboard.isInitialized) keyboard.setPolishingStreamActive(false)
                    polisher.polish(source, style, activePunctuation, terms, optimizeExpression, structureHint).getOrElse { fallbackError ->
                        Log.w(TAG, "Standard polishing request failed; inserting raw transcription", fallbackError)
                        source
                    }
                }
                val structuredText = StructuredExpressionFormatter.enforce(source, polishedText)
                VoiceProcessingResult.Success(applyPunctuationPreference(structuredText, activePunctuation))
            }
            if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
            when (result) {
                null -> {
                    if (sessionMode == KeyboardMode.VOICE && rawTranscript.isNotBlank()) {
                        commitResult(
                            applyPunctuationPreference(
                                StructuredExpressionFormatter.enforce(rawTranscript, localPolishFallback(rawTranscript)),
                                activePunctuation
                            ),
                            sessionId,
                            sessionMode,
                            instant = true,
                            historyType = if (polishMode) InputHistoryType.POLISH else InputHistoryType.DICTATION,
                            recordingDurationMs = recordingDurationMs
                        )
                    } else {
                        handleVoiceTimeout(sessionId, sessionMode)
                    }
                }
                is VoiceProcessingResult.Error -> {
                    activeVoiceMode = null
                    voiceState = VoiceUiState.Error(result.message)
                    render()
                }
                is VoiceProcessingResult.Success -> if (sessionMode == KeyboardMode.VOICE) {
                    commitResult(
                        result.text,
                        sessionId,
                        sessionMode,
                        result.instant,
                        if (polishMode) InputHistoryType.POLISH else InputHistoryType.DICTATION,
                        recordingDurationMs
                    )
                }
                is VoiceProcessingResult.Answer -> if (sessionMode == KeyboardMode.ASK) showAnswer(result.question, result.text, sessionId, sessionMode)
                is VoiceProcessingResult.WorkflowApplied -> if (sessionMode == KeyboardMode.ASK) {
                    activeVoiceMode = null
                    rawTranscript = ""
                    voiceState = VoiceUiState.Idle
                    toastFor(result.message, 1_800L)
                    render()
                }
            }
        }
    }

    private suspend fun processAskWorkflow(
        command: String,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        val localIntent = WorkflowRouter.route(command)
        val intent = when (localIntent) {
            is WorkflowIntent.Ambiguous -> polisher.classifyWorkflow(localIntent.command)
                .fold(
                    onSuccess = { WorkflowRouter.fromClassification(localIntent.command, it) },
                    onFailure = { WorkflowIntent.Answer(localIntent.command) }
                )
            else -> localIntent
        }
        ensureVoiceSession(sessionId, sessionMode)
        return when (intent) {
            is WorkflowIntent.Answer -> answerQuestion(intent.question, sessionId, sessionMode)
            is WorkflowIntent.ExactReplace -> applyExactWorkflowReplace(intent, sessionId, sessionMode)
            is WorkflowIntent.TargetedReplace -> applyTargetedWorkflowReplace(intent, sessionId, sessionMode)
            is WorkflowIntent.Continue -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.CONTINUE, sessionId, sessionMode)
            is WorkflowIntent.Summarize -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.SUMMARIZE, sessionId, sessionMode)
            is WorkflowIntent.Expand -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.EXPAND, sessionId, sessionMode)
            is WorkflowIntent.Translate -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.TRANSLATE, sessionId, sessionMode)
            is WorkflowIntent.Proofread -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.PROOFREAD, sessionId, sessionMode)
            is WorkflowIntent.Format -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.FORMAT, sessionId, sessionMode)
            is WorkflowIntent.Extract -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.EXTRACT, sessionId, sessionMode)
            is WorkflowIntent.Reply -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.REPLY, sessionId, sessionMode)
            is WorkflowIntent.Rewrite -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.REWRITE, sessionId, sessionMode)
            is WorkflowIntent.Polish -> applyModelWorkflowEdit(intent.instruction, WorkflowEditOperation.POLISH, sessionId, sessionMode)
            is WorkflowIntent.Ambiguous -> VoiceProcessingResult.Error("无法识别该命令")
        }
    }

    private suspend fun applyExactWorkflowReplace(
        intent: WorkflowIntent.ExactReplace,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        if (sensitiveField) return VoiceProcessingResult.Error("敏感输入框不支持文本修改")
        val snapshot = captureEditorSnapshot() ?: return VoiceProcessingResult.Error("当前输入框没有可修改的文本")
        if (!snapshot.text.contains(intent.source)) {
            return VoiceProcessingResult.Error("没有找到“${intent.source}”，未修改内容")
        }
        ensureVoiceSession(sessionId, sessionMode)
        val revised = snapshot.text.replace(intent.source, intent.replacement)
        return if (replaceEditorSnapshot(snapshot, revised)) {
            VoiceProcessingResult.WorkflowApplied("已${snapshot.scope.label}修改文本")
        } else {
            VoiceProcessingResult.Error("文本已变化，请重新执行命令")
        }
    }

    private suspend fun applyTargetedWorkflowReplace(
        intent: WorkflowIntent.TargetedReplace,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        if (sensitiveField) return VoiceProcessingResult.Error("敏感输入框不支持文本修改")
        val snapshot = captureEditorSnapshot() ?: return VoiceProcessingResult.Error("当前输入框没有可修改的文本")
        val revised = when (intent.target) {
            WorkflowReplaceTarget.PARENTHESES_CONTENT -> replaceNearestDelimitedContent(
                snapshot.text,
                snapshot.beforeCursor.length,
                intent.replacement,
                listOf('（' to '）', '(' to ')')
            )
            WorkflowReplaceTarget.QUOTED_CONTENT -> replaceNearestDelimitedContent(
                snapshot.text,
                snapshot.beforeCursor.length,
                intent.replacement,
                listOf('“' to '”', '‘' to '’', '"' to '"', '\'' to '\'')
            )
            WorkflowReplaceTarget.SELECTED_TEXT -> {
                if (snapshot.selectedText.isEmpty()) null
                else snapshot.text.replaceRange(
                    snapshot.beforeCursor.length,
                    snapshot.beforeCursor.length + snapshot.selectedText.length,
                    intent.replacement
                )
            }
        }
        if (revised == null) {
            val targetLabel = when (intent.target) {
                WorkflowReplaceTarget.PARENTHESES_CONTENT -> "括号里的内容"
                WorkflowReplaceTarget.QUOTED_CONTENT -> "引号里的内容"
                WorkflowReplaceTarget.SELECTED_TEXT -> "选中的内容"
            }
            return VoiceProcessingResult.Error("没有找到$targetLabel，未修改内容")
        }
        ensureVoiceSession(sessionId, sessionMode)
        return if (replaceEditorSnapshot(snapshot, revised)) {
            VoiceProcessingResult.WorkflowApplied("已${snapshot.scope.label}替换内容")
        } else {
            VoiceProcessingResult.Error("文本已变化，请重新执行命令")
        }
    }

    /** Replaces one named range: prefer the range under the cursor, otherwise the nearest one. */
    private fun replaceNearestDelimitedContent(
        text: String,
        cursor: Int,
        replacement: String,
        delimiters: List<Pair<Char, Char>>
    ): String? {
        data class Range(val start: Int, val end: Int)
        val ranges = buildList {
            delimiters.forEach { (opening, closing) ->
                val stack = ArrayDeque<Int>()
                text.forEachIndexed { index, character ->
                    if (opening == closing && character == opening) {
                        if (stack.isEmpty()) stack.addLast(index)
                        else add(Range(stack.removeLast() + 1, index))
                    } else {
                        when (character) {
                            opening -> stack.addLast(index)
                            closing -> if (stack.isNotEmpty()) add(Range(stack.removeLast() + 1, index))
                        }
                    }
                }
            }
        }
        val selected = ranges.minWithOrNull(
            compareBy<Range> { range ->
                when {
                    cursor < range.start -> range.start - cursor
                    cursor > range.end -> cursor - range.end
                    else -> 0
                }
            }.thenBy { it.end - it.start }
        ) ?: return null
        return text.replaceRange(selected.start, selected.end, replacement)
    }

    private suspend fun applyModelWorkflowEdit(
        instruction: String,
        operation: WorkflowEditOperation,
        sessionId: Long,
        sessionMode: KeyboardMode
    ): VoiceProcessingResult {
        if (sensitiveField) return VoiceProcessingResult.Error("敏感输入框不支持文本修改")
        val snapshot = captureEditorSnapshot() ?: return VoiceProcessingResult.Error("当前输入框没有可修改的文本")
        val terms = container.lexicon.all()
        val revised = polisher.editWorkflowText(
            instruction = workflowInstruction(operation, instruction),
            text = snapshot.text,
            style = style,
            punctuation = punctuation,
            lexicon = terms,
            optimizeExpression = operation in setOf(WorkflowEditOperation.POLISH, WorkflowEditOperation.FORMAT) && expressionOptimization
        ).getOrElse { error ->
            return VoiceProcessingResult.Error("${operation.label}失败：${error.message ?: "网络不可用"}")
        }.trim()
        ensureVoiceSession(sessionId, sessionMode)
        if (revised.isBlank()) return VoiceProcessingResult.Error("模型未返回修改后的文本")
        if (revised == snapshot.text) return VoiceProcessingResult.WorkflowApplied("内容没有变化")
        return if (replaceEditorSnapshot(snapshot, revised)) {
            VoiceProcessingResult.WorkflowApplied("已${snapshot.scope.label}${operation.label}")
        } else {
            VoiceProcessingResult.Error("文本已变化，请重新执行命令")
        }
    }

    private fun workflowInstruction(operation: WorkflowEditOperation, command: String): String = when (operation) {
        WorkflowEditOperation.CONTINUE ->
            "在不改动、删减或重复原文的前提下，沿用原文的语言、语气和上下文自然续写；只新增结尾内容，并返回原文加新增续写后的完整文本。"
        WorkflowEditOperation.SUMMARIZE ->
            "根据用户命令总结原文，保留核心事实、结论、数字和限制；删除冗余，输出摘要本身。用户补充：$command"
        WorkflowEditOperation.EXPAND ->
            "根据用户命令扩写原文，补足必要衔接、说明或细节；不得编造事实、数据或经历。用户补充：$command"
        WorkflowEditOperation.TRANSLATE ->
            "将原文按用户指定的目标语言自然翻译；未指定时翻译为自然英语。保留段落、专有名词、数字、链接和代码，只输出译文。用户补充：$command"
        WorkflowEditOperation.PROOFREAD ->
            "只修正原文中的错别字、语病、明显标点和空格问题；不得改变原意、文风、结构或补充内容。用户补充：$command"
        WorkflowEditOperation.FORMAT ->
            "按用户命令整理原文格式，保留全部信息；需要列表、待办、标题或 Markdown 时使用真实换行，不得凭空增加内容。用户补充：$command"
        WorkflowEditOperation.EXTRACT ->
            "从原文中提取用户要求的信息；没有指定类型时提取关键要点。只输出提取结果，不得编造未出现的信息。用户补充：$command"
        WorkflowEditOperation.REPLY ->
            "将原文视为待回复的消息或上下文，按用户要求生成一条可直接发送的回复；只输出回复正文，不要复述原文、说明或引号。用户补充：$command"
        WorkflowEditOperation.REWRITE -> command
        WorkflowEditOperation.POLISH -> command
    }

    private suspend fun answerQuestion(question: String, sessionId: Long, sessionMode: KeyboardMode): VoiceProcessingResult = try {
        val answer = StringBuilder()
        var receivedDelta = false
        polisher.answerStream(question).collect { delta ->
            ensureVoiceSession(sessionId, sessionMode)
            answer.append(delta)
            if (!receivedDelta) {
                voiceState = VoiceUiState.Preview(question = question, text = "", streaming = true)
                render()
            }
            voiceState = VoiceUiState.Preview(question = question, text = answer.toString(), streaming = true)
            if (::keyboard.isInitialized) {
                if (!receivedDelta) keyboard.setPolishingStreamActive(true)
                keyboard.notePolishingDelta()
            }
            receivedDelta = true
            render()
        }
        val text = answer.toString().trim()
        if (text.isBlank()) VoiceProcessingResult.Error("MiMo 未返回回答，请重试")
        else VoiceProcessingResult.Answer(question, text)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        ensureVoiceSession(sessionId, sessionMode)
        Log.w(TAG, "Question stream failed; retrying once", error)
        if (::keyboard.isInitialized) keyboard.setPolishingStreamActive(false)
        polisher.answer(question)
            .fold(
                onSuccess = { text -> VoiceProcessingResult.Answer(question, text) },
                onFailure = { fallbackError -> VoiceProcessingResult.Error("MiMo 问答失败：${fallbackError.message ?: "网络不可用"}") }
            )
    }

    private fun showAnswer(question: String, text: String, sessionId: Long, sessionMode: KeyboardMode) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) return
        rawTranscript = ""
        voiceState = VoiceUiState.Preview(question = question, text = text, streaming = false)
        persistHistory(InputHistoryType.QUESTION, text, question)
        render()
    }

    private fun handleVoiceTimeout(sessionId: Long, sessionMode: KeyboardMode) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) return
        activeVoiceMode = null
        rawTranscript = ""
        audioBuffer.clear()
        if (::keyboard.isInitialized) keyboard.playTimeout()
        toastFor("识别超时，请重试", 1_500L)
        voiceState = VoiceUiState.Idle
        render()
    }

    private fun commitResult(
        text: String,
        sessionId: Long,
        sessionMode: KeyboardMode,
        instant: Boolean = false,
        historyType: InputHistoryType? = null,
        recordingDurationMs: Long = activeRecordingDurationMs
    ) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) return
        val connection = currentInputConnection
        if (connection == null || sensitiveField) {
            activeVoiceMode = null
            voiceState = VoiceUiState.Error("当前输入框不可用，内容未插入")
            render()
            return
        }
        // Let the processing label disappear before the capsule restores and text begins to appear.
        voiceState = VoiceUiState.Idle
        render()
        commitTextJob?.cancel()
        commitTextJob = serviceScope.launch {
            delay(PROCESSING_LABEL_FADE_MS)
            if (!isCurrentVoiceSession(sessionId, sessionMode) || currentInputConnection !== connection || sensitiveField) return@launch
            if (instant) {
                connection.commitText(text, 1)
                lastCommittedText = text
                lastCommitConnection = connection
                historyType?.let { persistDictation(it, text, recordingDurationMs) }
                if (isCurrentVoiceSession(sessionId, sessionMode)) activeVoiceMode = null
                return@launch
            }
            var typewriterIndex = 0
            while (typewriterIndex < text.length) {
                if (!isCurrentVoiceSession(sessionId, sessionMode)) return@launch
                // Advance by code point: an emoji must never be split into two
                // surrogate-half commits.
                val codePoint = text.codePointAt(typewriterIndex)
                connection.commitText(String(Character.toChars(codePoint)), 1)
                typewriterIndex += Character.charCount(codePoint)
                delay(TYPEWRITER_CHARACTER_DELAY_MS)
            }
            lastCommittedText = text
            lastCommitConnection = connection
            historyType?.let { persistDictation(it, text, recordingDurationMs) }
            if (isCurrentVoiceSession(sessionId, sessionMode)) activeVoiceMode = null
        }
    }

    private fun cancelActiveVoiceSession() {
        finishVoiceJob?.cancel()
        finishVoiceJob = null
        voiceSessionId += 1
        activeVoiceMode = null
        activeVoicePolish = false
        activeVoiceTranslation = false
        recorder.stop()
        voiceStartedAtMs = 0L
        activeRecordingDurationMs = 0L
        rawTranscript = ""
        audioBuffer.clear()
        if (::keyboard.isInitialized) keyboard.setPolishingStreamActive(false)
        voiceState = VoiceUiState.Idle
    }

    private fun isCurrentVoiceSession(sessionId: Long, sessionMode: KeyboardMode): Boolean =
        voiceSessionId == sessionId && activeVoiceMode == sessionMode

    private fun ensureVoiceSession(sessionId: Long, sessionMode: KeyboardMode) {
        if (!isCurrentVoiceSession(sessionId, sessionMode)) throw CancellationException("Stale voice session")
    }

    private fun applyPunctuationPreference(text: String, preference: PunctuationPreference): String {
        val cleaned = cleanSymbols(text)
        return when (preference) {
            PunctuationPreference.SMART -> cleaned
            PunctuationPreference.SPACES -> cleaned
                .split('\n')
                .joinToString("\n") { line ->
                    line.replace(Regex("[，。！？、；：,.!?;:…—()（）\\[\\]{}\\\"'“”]+"), " ")
                        .replace(Regex("[ \\t]+"), " ")
                        .trim()
                }
                .trim()
            PunctuationPreference.NO_END -> cleaned
            .replace(Regex("[\\s，。！？、；：,.!?;:…—]+$"), "")
            .trimEnd()
        }
    }

    private fun cleanSymbols(text: String): String = text
        .replace("\r\n", "\n")
        .replace(Regex("[\\u00A0\\t\\r]+"), " ")
        .lineSequence()
        .joinToString("\n") { line ->
            line.replace(Regex(" {2,}"), " ")
                .replace(Regex(" +([，。！？、；：,.!?;:])"), "$1")
                .trim()
        }
        .replace(Regex("\\n{3,}"), "\n\n")
        .replace(Regex("([（(【\\[])[\\s]+"), "$1")
        .replace(Regex("[\\s]+([）)】\\]])"), "$1")
        .replace(Regex("([，。！？、；：,.!?;:]){2,}")) { match ->
            val value = match.value
            when {
                value.any { it == '？' || it == '?' } -> "？"
                value.any { it == '！' || it == '!' } -> "！"
                value.any { it == '。' || it == '.' } -> "。"
                value.any { it == '；' || it == ';' } -> "；"
                value.any { it == '：' || it == ':' } -> "："
                value.any { it == '、' } -> "、"
                else -> "，"
            }
        }
        .trim()

    private fun localPolishFallback(text: String): String = text
        .replace(Regex("[，,。.!！?？；;：:、]{2,}"), "。")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^(嗯+|啊+|呃+|额+|那个|就是)[，,。.!！?？；;：:\\s]*"), "")
        .replace(Regex("[\\s，,。.!！?？；;：:、]*(嗯+|啊+|呃+|额+)[\\s，,。.!！?？；;：:、]*"), "")
        .trim()

    private fun shouldUseDeepPolish(text: String): Boolean {
        if (StructuredExpressionFormatter.needsStructure(text)) return true
        val normalized = text.replace(Regex("\\s+"), "")
        val cues = listOf(
            "一是", "二是", "三是", "第一", "第二", "第三",
            "首先", "其次", "最后", "分别是", "分为", "几件事",
            "待办", "步骤", "提纲", "框架", "开头段", "中间段", "结尾段",
            "正方观点", "反方观点"
        )
        return cues.count { normalized.contains(it) } >= 2 ||
            cues.any { normalized.contains(it) } &&
            (normalized.contains("和") || normalized.contains("、") || normalized.contains("还有"))
    }

    private fun captureEditorSnapshot(): EditorTextSnapshot? {
        val connection = currentInputConnection ?: return null
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        val fullText = extracted?.text?.toString()
        if (!fullText.isNullOrEmpty() && extracted.startOffset == 0 && extracted.partialStartOffset < 0) {
            val start = extracted.selectionStart.coerceIn(0, fullText.length)
            val end = extracted.selectionEnd.coerceIn(start, fullText.length)
            return EditorTextSnapshot(
                connection = connection,
                editorSessionId = editorSessionId,
                text = fullText,
                beforeCursor = fullText.substring(0, start),
                selectedText = fullText.substring(start, end),
                afterCursor = fullText.substring(end),
                scope = WorkflowTextScope.FULL,
                fullText = fullText,
                selectionStart = start,
                selectionEnd = end
            )
        }

        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            return EditorTextSnapshot(
                connection = connection,
                editorSessionId = editorSessionId,
                text = selected,
                beforeCursor = "",
                selectedText = selected,
                afterCursor = "",
                scope = WorkflowTextScope.SELECTION
            )
        }

        val before = connection.getTextBeforeCursor(MAX_WORKFLOW_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(MAX_WORKFLOW_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val paragraphBefore = before.substringAfterLast('\n')
        val paragraphAfter = after.substringBefore('\n')
        val paragraph = paragraphBefore + paragraphAfter
        if (paragraph.isBlank()) return null
        return EditorTextSnapshot(
            connection = connection,
            editorSessionId = editorSessionId,
            text = paragraph,
            beforeCursor = paragraphBefore,
            selectedText = "",
            afterCursor = paragraphAfter,
            scope = WorkflowTextScope.PARAGRAPH
        )
    }

    /**
     * The editor may change while the model request is in flight. Validate both the
     * IME connection and the exact range around the cursor before a destructive edit.
     */
    private fun replaceEditorSnapshot(snapshot: EditorTextSnapshot, replacement: String): Boolean {
        if (snapshot.connection !== currentInputConnection || snapshot.editorSessionId != editorSessionId || sensitiveField) return false
        val connection = snapshot.connection
        if (snapshot.fullText != null) {
            val current = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return false
            if (current.text?.toString() != snapshot.fullText ||
                current.selectionStart != snapshot.selectionStart || current.selectionEnd != snapshot.selectionEnd
            ) return false
        } else {
            val currentSelected = connection.getSelectedText(0)?.toString().orEmpty()
            val currentBefore = connection.getTextBeforeCursor(snapshot.beforeCursor.length, 0)?.toString().orEmpty()
            val currentAfter = connection.getTextAfterCursor(snapshot.afterCursor.length, 0)?.toString().orEmpty()
            if (currentSelected != snapshot.selectedText ||
                currentBefore != snapshot.beforeCursor || currentAfter != snapshot.afterCursor
            ) return false
        }
        val batchStarted = connection.beginBatchEdit()
        return try {
            // commitText first removes a live selection. The remaining deletion is
            // relative to the now-collapsed cursor, avoiding global setSelection.
            if (snapshot.selectedText.isNotEmpty()) connection.commitText("", 1)
            if (snapshot.beforeCursor.isNotEmpty() || snapshot.afterCursor.isNotEmpty()) {
                connection.deleteSurroundingText(snapshot.beforeCursor.length, snapshot.afterCursor.length)
            }
            connection.commitText(replacement, 1)
        } finally {
            if (batchStarted) connection.endBatchEdit()
        }
    }

    private enum class WorkflowTextScope(val label: String) {
        FULL("全文"),
        SELECTION("选中文本"),
        PARAGRAPH("当前段落")
    }

    private data class EditorTextSnapshot(
        val connection: InputConnection,
        val editorSessionId: Long,
        val text: String,
        val beforeCursor: String,
        val selectedText: String,
        val afterCursor: String,
        val scope: WorkflowTextScope,
        val fullText: String? = null,
        val selectionStart: Int = -1,
        val selectionEnd: Int = -1
    )

    private enum class WorkflowEditOperation(val label: String) {
        CONTINUE("续写"),
        SUMMARIZE("总结"),
        EXPAND("扩写"),
        TRANSLATE("翻译"),
        PROOFREAD("改错"),
        FORMAT("整理格式"),
        EXTRACT("提取"),
        REPLY("生成回复"),
        REWRITE("修改"),
        POLISH("润色")
    }

    /*
     * The service only ever keeps this small result in memory. It avoids a late HTTP
     * completion inserting content after the visual timeout has restored the keyboard.
     */
    private sealed interface VoiceProcessingResult {
        data class Success(val text: String, val instant: Boolean = false) : VoiceProcessingResult
        data class Answer(val question: String, val text: String) : VoiceProcessingResult
        data class WorkflowApplied(val message: String) : VoiceProcessingResult
        data class Error(val message: String) : VoiceProcessingResult
    }

    override fun undoLastInsert() {
        val text = lastCommittedText ?: return
        if (lastCommitConnection !== currentInputConnection) {
            toast("输入焦点已变更，无法撤销")
            return
        }
        currentInputConnection?.deleteSurroundingText(text.length, 0)
        lastCommittedText = null
        voiceState = VoiceUiState.Idle
        render()
    }

    override fun typeEnglish(value: String) {
        if (mode == KeyboardMode.VOICE) {
            insertPolishDraft(value)
            polishOptions = emptyList()
            voiceState = VoiceUiState.Idle
            render()
            return
        }
        if (value != " " || mode == KeyboardMode.SYMBOLS) resetDoubleSpacePeriod()
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            finishCompositionThen { commitTextAndPredict(value) }
        } else if (mode == KeyboardMode.SYMBOLS && modeBeforeSymbols == KeyboardMode.PINYIN && pinyinRawBuffer.isNotBlank()) {
            enqueuePinyin {
                commitCurrentPinyin()
                commitTextAndPredict(value)
            }
        } else if (mode == KeyboardMode.SYMBOLS && englishBuffer.isNotBlank()) {
            commitEnglishComposition(false)
            commitTextAndPredict(value)
        } else if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) {
            // The emoji panel and the @ key reach this path with a live English
            // composition; a direct commit would let editors drop the letters.
            commitEnglishComposition(false)
            commitTextAndPredict(value)
        } else commitTextAndPredict(value)
    }

    override fun punctuationKeyTapped() {
        autoPunctuationPending = false
        typeEnglish("，")
        autoPunctuationPending = true
        autoPunctuationAtMs = SystemClock.elapsedRealtime()
        autoPunctuationText = "，"
    }

    override fun choosePunctuation(symbol: String) {
        val autoPunctFresh = autoPunctuationPending &&
            SystemClock.elapsedRealtime() - autoPunctuationAtMs <= AUTO_PUNCTUATION_WINDOW_MS
        if (mode == KeyboardMode.VOICE) {
            // The Super Say draft lives outside the InputConnection, so the
            // placeholder comma must be removed from the draft buffer instead.
            if (autoPunctFresh && polishCursor == polishDraft.length && polishDraft.endsWith(autoPunctuationText)) {
                val edit = DraftEditor.deleteBefore(polishDraft, polishCursor)
                polishDraft = edit.text
                polishCursor = edit.cursor
                polishOptions = emptyList()
            }
            autoPunctuationPending = false
            typeEnglish(symbol)
            return
        }
        val connection = currentInputConnection
        if (autoPunctFresh && connection != null && !sensitiveField &&
            pinyinRawBuffer.isBlank() && englishBuffer.isBlank() &&
            connection.getTextBeforeCursor(1, 0)?.toString() == autoPunctuationText
        ) {
            connection.deleteSurroundingText(1, 0)
        }
        autoPunctuationPending = false
        typeEnglish(symbol)
    }

    override fun typeEnglishLetter(value: String) {
        resetDoubleSpacePeriod()
        autoPunctuationPending = false
        clearPredictions()
        englishBuffer += value
        englishCandidates = EnglishCandidateEngine.candidates(englishBuffer)
        updateComposingText(englishBuffer)
        render()
    }

    override fun typePinyin(value: String) {
        engineIdleReleaseJob?.cancel()
        if (value.length != 1) return
        autoPunctuationPending = false
        if (pendingPinyinLetters.isEmpty()) clearPredictions()
        pendingPinyinMutations += 1
        pendingPinyinLetters.append(value.lowercase())
        if (!pinyinBatchScheduled) {
            pinyinBatchScheduled = true
            Choreographer.getInstance().postFrameCallback { flushPendingPinyinBatch() }
        }
    }

    override fun chooseCandidate(candidate: PinyinCandidate) {
        flushPendingPinyinBatch()
        val targetConnection = currentInputConnection
        enqueuePinyin {
            if (candidate.directCommit) {
                commitDirectPinyin(candidate.text, targetConnection)
            } else {
                val current = pinyinDecoder.currentState()
                val verified = current.candidates.firstOrNull {
                    !it.directCommit && it.index == candidate.index && it.text == candidate.text
                } ?: current.candidates.firstOrNull { !it.directCommit && it.text == candidate.text }
                if (verified == null) {
                    applyPinyinState(current, allowCommit = false)
                    return@enqueuePinyin
                }
                val state = pinyinDecoder.selectCandidate(verified.index)
                // Selecting a segment of a long Rime composition does not commit
                // text yet. It only confirms a segment inside the live preedit.
                // The engine's commit is authoritative; using the tapped text as
                // a fallback here duplicates it when the final segment commits.
                commitNativePinyin(state, state.committedText, targetConnection)
            }
        }
    }

    override fun choosePinyinSpelling(spelling: String) {
        flushPendingPinyinBatch()
        enqueuePinyin { applyPinyinState(pinyinDecoder.selectSpelling(spelling), allowCommit = false) }
    }

    override fun dismissPredictions() {
        clearPredictions(clearContext = true)
    }

    /** 打开内置的全屏 AI 对话页；回答由用户预览后插回当前输入框。 */
    override fun openAiChat() {
        if (sensitiveField) return
        runCatching {
            startActivity(
                android.content.Intent(this, AiChatActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("无法打开 AI 助手") }
    }

    override fun copySelection() {
        val connection = currentInputConnection ?: return
        val selected = runCatching { connection.getSelectedText(0)?.toString() }.getOrNull()
        val text = if (!selected.isNullOrBlank()) {
            selected
        } else {
            runCatching {
                val before = connection.getTextBeforeCursor(20000, 0)?.toString().orEmpty()
                val after = connection.getTextAfterCursor(20000, 0)?.toString().orEmpty()
                before + after
            }.getOrDefault("")
        }
        if (text.isBlank()) {
            toast("没有可复制的内容")
            return
        }
        runCatching {
            clipboardManager.setPrimaryClip(
                android.content.ClipData.newPlainText("AI 输入法", text)
            )
            toast("已复制")
        }
    }

    override fun pasteClipboardText() {
        val text = runCatching {
            clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull().orEmpty()
        if (text.isBlank()) {
            toast("剪贴板里没有文字")
            return
        }
        finishCompositionThen { currentInputConnection?.commitText(text, 1) }
    }

    override fun selectAllText() {
        val connection = currentInputConnection ?: return
        runCatching {
            val before = connection.getTextBeforeCursor(50000, 0)?.length ?: 0
            val after = connection.getTextAfterCursor(50000, 0)?.length ?: 0
            connection.setSelection(0, before + after)
        }
    }

    /**
     * Consumes text produced by the chat page.
     *
     * The chat page covers the host app, so the input connection may be gone by
     * the time the broadcast arrives. In that case the text stays queued and is
     * committed from [onStartInputView] when the keyboard comes back.
     */
    private fun commitPendingAiText() {
        if (!inputViewActive) return
        val text = AiInsertBridge.consume() ?: return
        if (sensitiveField) return
        val connection = currentInputConnection ?: run {
            AiInsertBridge.offer(text)
            return
        }
        runCatching {
            connection.finishComposingText()
            connection.commitText(text, 1)
        }.onSuccess {
            lastCommittedText = text
            lastCommitConnection = connection
        }.onFailure {
            AiInsertBridge.offer(text)
        }
    }

    /**
     * 26 键是唯一布局，聊天页无需再切换；保留方法只是为了不打断调用点。
     */
    private fun applyChatKeyboardPreference(info: EditorInfo?) = Unit

    private fun restoreChatKeyboardPreference() = Unit

    override fun chooseEnglishCandidate(value: String) {
        resetDoubleSpacePeriod()
        commitTextAndPredict(value)
        recordEnglishSelection(value)
        englishBuffer = ""
        englishCandidates = emptyList()
        updateComposingText(englishBuffer)
        render()
    }

    override fun choosePrediction(candidate: PredictionCandidate) {
        resetDoubleSpacePeriod()
        commitTextAndPredict(candidate.text, candidate.appendSpace)
    }

    override fun commitEnglishComposition(addSpace: Boolean) {
        if (!addSpace) resetDoubleSpacePeriod()
        val value = englishCandidates.firstOrNull()?.text ?: englishBuffer
        if (value.isNotBlank()) {
            commitTextAndPredict(value)
            recordEnglishSelection(value)
        }
        if (addSpace) currentInputConnection?.commitText(" ", 1)
        englishBuffer = ""
        englishCandidates = emptyList()
        updateComposingText(englishBuffer)
        render()
    }

    override fun pressTextSpace(pinyin: Boolean) {
        if (pinyin && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin {
                commitCurrentPinyin()
            }
            resetDoubleSpacePeriod()
            return
        }
        if (!pinyin && englishBuffer.isNotBlank()) {
            commitEnglishComposition(true)
            markTextSpace()
            return
        }
        if (mode == KeyboardMode.VOICE) {
            insertPolishDraft(" ")
            render()
            return
        }
        insertTextSpace(pinyin)
    }

    override fun backspace() {
        resetDoubleSpacePeriod()
        autoPunctuationPending = false
        clearPredictions()
        flushPendingPinyinBatch()
        if ((mode == KeyboardMode.PINYIN || mode == KeyboardMode.VOICE) && (pinyinRawBuffer.isNotEmpty() || pendingPinyinMutations > 0)) {
            pendingPinyinMutations += 1
            enqueuePinyin {
                try {
                    applyPinyinState(pinyinDecoder.backspace(), allowCommit = false)
                } finally {
                    pendingPinyinMutations -= 1
                }
            }
        } else if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) {
            englishBuffer = englishBuffer.dropLast(1)
            englishCandidates = EnglishCandidateEngine.candidates(englishBuffer)
            updateComposingText(englishBuffer)
            render()
        } else if (mode == KeyboardMode.VOICE && polishDraft.isNotEmpty()) {
            val edit = DraftEditor.deleteBefore(polishDraft, polishCursor)
            polishDraft = edit.text
            polishCursor = edit.cursor
            polishOptions = emptyList()
            render()
        } else {
            deletePreviousEditorText()
        }
    }

    override fun canBackspace(): Boolean {
        if ((mode == KeyboardMode.PINYIN || mode == KeyboardMode.VOICE) && (pinyinRawBuffer.isNotEmpty() || pendingPinyinMutations > 0)) return true
        if (mode == KeyboardMode.VOICE && polishDraft.isNotEmpty()) return true
        if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) return true
        val connection = currentInputConnection ?: return false
        return connection.getSelectedText(0)?.isNotEmpty() == true ||
            connection.getTextBeforeCursor(2, 0)?.isNotEmpty() == true
    }

    /** Deletes one user-visible character, never a single UTF-16 surrogate unit. */
    private fun deletePreviousEditorText(): Boolean {
        val connection = currentInputConnection ?: return false
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            return connection.commitText("", 1) || sendDeleteKey(connection)
        }
        val preceding = connection.getTextBeforeCursor(MAX_DELETE_CONTEXT_CHARS, 0)?.toString().orEmpty()
        if (preceding.isEmpty()) return false
        val deleteLength = previousGraphemeLength(preceding)
        return connection.deleteSurroundingText(deleteLength, 0) || sendDeleteKey(connection)
    }

    private fun previousGraphemeLength(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
        iterator.setText(text)
        val start = iterator.preceding(text.length)
        return if (start == BreakIterator.DONE) {
            Character.charCount(text.codePointBefore(text.length))
        } else {
            (text.length - start).coerceAtLeast(1)
        }
    }

    private fun sendDeleteKey(connection: InputConnection): Boolean =
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) &&
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))

    override fun moveCursorBy(delta: Int) {
        if (delta == 0) return
        finishCompositionThen {
            val connection = currentInputConnection ?: return@finishCompositionThen
            val keyCode = if (delta > 0) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT
            val requested = kotlin.math.abs(delta).coerceAtMost(12)
            val available = if (delta > 0) {
                connection.getTextAfterCursor(requested, 0)?.length ?: 0
            } else {
                connection.getTextBeforeCursor(requested, 0)?.length ?: 0
            }
            repeat(minOf(requested, available)) {
                connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
            }
        }
        clearPredictions()
    }

    override fun enter() {
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin { commitRawPinyin() }
        } else {
            finishCompositionThen { performEnterForField() }
        }
    }

    /**
     * The enter key used to hard-code IME_ACTION_SEND, which silently did
     * nothing in search bars and login forms. Follow the focused field's
     * declared action instead and only fall back to send for plain fields.
     */
    private fun performEnterForField() {
        val info = currentInputEditorInfo
        val noEnterAction = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (noEnterAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.commitText("\n", 1)
        } else {
            currentInputConnection?.performEditorAction(action)
        }
    }

    private fun enterActionLabel(): String {
        val info = currentInputEditorInfo ?: return "发送"
        info.actionLabel?.takeIf { it.isNotBlank() }?.let { return it.toString() }
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return when {
            noEnterAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED -> "换行"
            action == EditorInfo.IME_ACTION_GO -> "前往"
            action == EditorInfo.IME_ACTION_SEARCH -> "搜索"
            action == EditorInfo.IME_ACTION_NEXT -> "下一步"
            action == EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "发送"
        }
    }

    override fun newline() {
        finishCompositionThen { currentInputConnection?.commitText("\n", 1) }
    }

    private fun finishCompositionThen(action: () -> Unit) {
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotEmpty() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin {
                commitCurrentPinyin()
                action()
            }
        } else if (mode == KeyboardMode.ENGLISH && englishBuffer.isNotEmpty()) {
            commitEnglishComposition(false)
            action()
        } else action()
    }

    override fun insertAt() = typeEnglish("@")

    override fun switchInputMethod() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    override fun toggleSymbols() {
        val toggle = {
            clearPredictions()
            mode = if (mode == KeyboardMode.SYMBOLS) modeBeforeSymbols else {
                // Remember the exact mode we came from so returning from the
                // symbol page restores VOICE/ASK/CLIPBOARD instead of falling
                // back to ENGLISH.
                modeBeforeSymbols = mode
                KeyboardMode.SYMBOLS
            }
            render()
        }
        if (mode == KeyboardMode.PINYIN && (pinyinRawBuffer.isNotBlank() || pendingPinyinMutations > 0)) {
            enqueueAfterPendingPinyin {
            commitCurrentPinyin()
            toggle()
            }
        } else toggle()
    }

    private fun render() {
        if (!::keyboard.isInitialized) return
        // Rime completion callbacks run on a dedicated worker thread. Choreographer
        // is thread-local and must only be obtained from the main looper.
        mainHandler.post {
            if (!::keyboard.isInitialized || renderPending) return@post
            renderPending = true
            Choreographer.getInstance().postFrameCallback(renderFrameCallback)
        }
    }

    private fun currentRecentClipboard(): String = recentClipboardContent.takeIf {
        recentClipboardPasteEnabled && !sensitiveField &&
            SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs <= RECENT_CLIPBOARD_WINDOW_MS
            && SystemClock.elapsedRealtime() <= recentClipboardVisibleUntilMs
    }.orEmpty()

    private fun shouldAutoCapitalizeEnglish(): Boolean {
        if (!englishAutoCapitalize || mode != KeyboardMode.ENGLISH || englishBuffer.isNotBlank()) return false
        val preceding = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString()?.trimEnd().orEmpty()
        return preceding.isBlank() || preceding.lastOrNull() in AUTO_CAPITALIZE_AFTER
    }

    private fun insertTextSpace(pinyin: Boolean) {
        val connection = currentInputConnection ?: return
        val now = SystemClock.elapsedRealtime()
        val shouldInsertPeriod = doubleSpacePeriod && connection === lastTextSpaceConnection &&
            now - lastTextSpaceAtMs <= DOUBLE_SPACE_PERIOD_WINDOW_MS
        if (shouldInsertPeriod) {
            connection.deleteSurroundingText(1, 0)
            connection.commitText(if (pinyin) "。" else ". ", 1)
            resetDoubleSpacePeriod()
        } else {
            connection.commitText(" ", 1)
            lastTextSpaceAtMs = now
            lastTextSpaceConnection = connection
        }
        render()
    }

    private fun markTextSpace() {
        lastTextSpaceAtMs = SystemClock.elapsedRealtime()
        lastTextSpaceConnection = currentInputConnection
    }

    private fun resetDoubleSpacePeriod() {
        lastTextSpaceAtMs = 0L
        lastTextSpaceConnection = null
    }

    private fun toKeyboardMode(preference: KeyboardModePreference): KeyboardMode = when (preference) {
        KeyboardModePreference.VOICE -> KeyboardMode.VOICE
        KeyboardModePreference.TEXT -> KeyboardMode.TEXT
        KeyboardModePreference.ASK -> KeyboardMode.ASK
        KeyboardModePreference.CLIPBOARD -> KeyboardMode.CLIPBOARD
    }

    private fun isConfiguredMode(candidate: KeyboardMode): Boolean =
        candidate in visibleKeyboardModes ||
            (candidate in listOf(KeyboardMode.PINYIN, KeyboardMode.ENGLISH) && KeyboardMode.TEXT in visibleKeyboardModes)

    private fun initialModeFor(configured: KeyboardMode): KeyboardMode =
        if (configured == KeyboardMode.TEXT) KeyboardMode.PINYIN else configured

    private fun applyConfiguredStartupMode() {
        val requested = when (keyboardStartupMode) {
            KeyboardStartupMode.LAST_USED -> retainedKeyboardMode ?: loadRetainedKeyboardMode()
            KeyboardStartupMode.VOICE -> KeyboardMode.VOICE
            KeyboardStartupMode.PINYIN -> KeyboardMode.PINYIN
            KeyboardStartupMode.ENGLISH -> KeyboardMode.ENGLISH
            KeyboardStartupMode.ASK -> KeyboardMode.ASK
            KeyboardStartupMode.CLIPBOARD -> KeyboardMode.CLIPBOARD
        }
        if (requested != null && isConfiguredMode(requested)) mode = requested
    }

    private fun capturePrimaryClipboard() {
        val content = runCatching {
            clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
        if (content.isBlank() || isSensitiveClipboard(content)) return
        if (recentClipboardPasteEnabled) {
            recentClipboardContent = content
            recentClipboardCapturedAtMs = SystemClock.elapsedRealtime()
            if (inputViewActive) presentRecentClipboardForInputView()
            mainHandler.postDelayed({
                if (SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs >= RECENT_CLIPBOARD_WINDOW_MS) {
                    clearRecentClipboard()
                    render()
                }
            }, RECENT_CLIPBOARD_WINDOW_MS)
            render()
        }
        if (clipboardHistoryEnabled && content != lastClipboardContent) {
            lastClipboardContent = content
            serviceScope.launch(Dispatchers.IO) {
                container.clipboard.record(content)
            }
        }
    }

    private fun clearRecentClipboard() {
        recentClipboardContent = ""
        recentClipboardCapturedAtMs = 0L
        recentClipboardVisibleUntilMs = 0L
    }

    private fun presentRecentClipboardForInputView() {
        if (!recentClipboardPasteEnabled || recentClipboardContent.isBlank() ||
            SystemClock.elapsedRealtime() - recentClipboardCapturedAtMs > RECENT_CLIPBOARD_WINDOW_MS) {
            clearRecentClipboard()
            return
        }
        val expiresAt = SystemClock.elapsedRealtime() + QUICK_PASTE_VISIBLE_WINDOW_MS
        recentClipboardVisibleUntilMs = expiresAt
        mainHandler.postDelayed({
            if (recentClipboardVisibleUntilMs == expiresAt && SystemClock.elapsedRealtime() >= expiresAt) {
                clearRecentClipboard()
                render()
            }
        }, QUICK_PASTE_VISIBLE_WINDOW_MS)
        render()
    }

    private fun isSensitiveClipboard(content: String): Boolean {
        if (content.length > MAX_CLIPBOARD_CONTENT_LENGTH) return true
        val normalized = content.trim()
        if (normalized.matches(Regex("^\\d{6,8}$"))) return true
        return SENSITIVE_CLIPBOARD_MARKERS.containsMatchIn(normalized)
    }

    private fun refreshPinyinCandidates() {
        // Switching into 拼 must never clear a composition created by the prior key event.
        render()
    }

    private fun applyPinyinState(state: PinyinSessionState, allowCommit: Boolean = true) {
        if (allowCommit) state.committedText?.let { committed ->
            if (mode == KeyboardMode.VOICE) {
                insertPolishDraft(committed)
                polishOptions = emptyList()
                voiceState = VoiceUiState.Idle
            } else {
                commitTextAndPredict(committed)
            }
        }
        // A live composition owns the candidate bar. Invalidate async
        // predictions before they can overwrite Rime's remaining candidates.
        if (state.rawComposition.isNotBlank()) clearPredictions()
        val previousRaw = pinyinRawBuffer
        val previousDisplay = pinyinBuffer
        pinyinBuffer = displayPinyinComposition(state, previousRaw, previousDisplay)
        pinyinRawBuffer = state.rawComposition
        pinyinCandidates = state.candidates
        spellingOptions = state.spellingOptions
        selectedSpelling = state.selectedSpelling
        updateComposingText(pinyinBuffer)
        render()
    }

    /**
     * Rime's T9 schema receives numeric key codes. Keep that exact code for
     * decoding and deletion, but never expose it as composing text to apps.
     */
    private fun displayPinyinComposition(
        state: PinyinSessionState,
        previousRaw: String = pinyinRawBuffer,
        previousDisplay: String = pinyinBuffer
    ): String {
        if (chineseKeyboardLayout != ChineseKeyboardLayout.NINE_KEY) return state.preedit
        // The T9 schema may retain separators in context.input while the user
        // enters successive syllables. Decode against its digit sequence rather
        // than requiring the raw context to contain digits only.
        val t9Code = state.rawComposition.filter(Char::isDigit)
        if (t9Code.isBlank()) return state.preedit
        // The T9 schema receives digits internally. Pick a candidate whose
        // reading maps back to those exact digits before exposing a composing
        // string. This prevents raw codes such as "64 426" from leaking into
        // the target editor while still showing the complete "nihao" reading.
        val readings = state.candidates.asSequence()
            .map { candidate -> candidate.reading.ifBlank { pinyinDecoder.pinyinForDisplay(candidate.text) }.replace(" ", "") }
            .filter(String::isNotBlank)
            .toList()
        return NineKeyComposition.display(t9Code, state.preedit, readings, previousRaw, previousDisplay)
    }

    private fun t9CodeFor(pinyin: String): String = NineKeyComposition.codeFor(pinyin)

    private suspend fun commitCurrentPinyin() {
        val current = pinyinDecoder.currentState()
        val candidate = current.candidates.firstOrNull() ?: return
        if (candidate.directCommit) commitDirectPinyin(candidate.text)
        else {
            val state = pinyinDecoder.selectCandidate(candidate.index)
            commitNativePinyin(state, state.committedText)
        }
    }

    private suspend fun commitRawPinyin() {
        val state = pinyinDecoder.currentState()
        val raw = state.rawComposition
        val committed = if (raw.all(Char::isDigit)) {
            state.candidates.firstOrNull()?.text.orEmpty()
        } else state.preedit.ifBlank { pinyinBuffer }
        pinyinDecoder.clear()
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        updateComposingText("")
        if (committed.isNotBlank()) commitTextAndPredict(committed)
        render()
    }

    private suspend fun commitDirectPinyin(text: String, targetConnection: InputConnection? = currentInputConnection) {
        if (!sensitiveField && pinyinDecoder.learnCandidate(text)) {
            serviceScope.launch(Dispatchers.IO) {
                container.pinyinLearning.record(text)
                lastPinyinLearning = container.pinyinLearning.all()
            }
        }
        pinyinDecoder.clear()
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        commitPinyinCandidateAndPredict(text, targetConnection)
        render()
    }

    private suspend fun commitNativePinyin(
        state: PinyinSessionState,
        text: String?,
        targetConnection: InputConnection? = currentInputConnection
    ) {
        if (text.isNullOrBlank()) {
            // Partial selection: Rime keeps the whole key sequence in
            // context.input, so rawComposition alone cannot tell us whether text
            // was committed. Preserve the decoder and render its actual preedit.
            applyPinyinState(state, allowCommit = false)
        } else {
            commitPinyinCandidate(text, targetConnection, schedulePrediction = true)
            pinyinDecoder.clear()
            pinyinBuffer = ""
            pinyinRawBuffer = ""
            pinyinCandidates = emptyList()
            render()
        }
    }

    /**
     * Candidate selection must not clear composing text before the selected text
     * reaches the editor. Several editors confirm that transient empty composing
     * state on their next Enter event, which caused a missing or duplicate pick.
     */
    private fun commitPinyinCandidate(
        text: String,
        targetConnection: InputConnection?,
        schedulePrediction: Boolean
    ) {
        if (text.isBlank()) return
        if (mode == KeyboardMode.VOICE) {
            insertPolishDraft(text)
            polishOptions = emptyList()
            voiceState = VoiceUiState.Idle
            render()
            return
        }
        val connection = targetConnection ?: return
        if (connection !== currentInputConnection) return
        val batchStarted = connection.beginBatchEdit()
        try {
            if (connection.setComposingText(text, 1)) {
                connection.finishComposingText()
            } else {
                connection.commitText(text, 1)
            }
        } finally {
            if (batchStarted) connection.endBatchEdit()
        }
        if (schedulePrediction) schedulePredictions(text, connection)
    }

    private fun commitPinyinCandidateAndPredict(text: String, targetConnection: InputConnection?) =
        commitPinyinCandidate(text, targetConnection, schedulePrediction = true)

    private fun commitTextAndPredict(text: String, appendSpace: Boolean = false) {
        if (text.isBlank()) return
        val connection = currentInputConnection ?: return
        connection.commitText(text, 1)
        if (appendSpace) connection.commitText(" ", 1)
        schedulePredictions(text, connection)
    }

    private fun schedulePredictions(text: String, connection: InputConnection) {
        if (!predictionEnabled || sensitiveField) {
            clearPredictions()
            return
        }
        val requestId = ++predictionRequestId
        predictionCandidates = emptyList()
        render()
        predictionOperations.trySend {
            ensureJiebaReady()
            val appended = predictionEngine.tokenize(text)
            if (appended.isEmpty()) return@trySend
            val previous = predictionContextTokens
            val combined = (previous + appended).takeLast(PREDICTION_CONTEXT_LIMIT)
            if (predictionLearningEnabled) {
                val transitions = predictionEngine.transitionsForAppend(previous, appended)
                withContext(Dispatchers.IO) {
                    transitions.forEach { (context, target) -> container.predictionLearning.record(context, target) }
                }
            }
            predictionContextTokens = combined
            val contexts = predictionEngine.contextsFor(combined)
            val learned = if (predictionLearningEnabled && contexts.isNotEmpty()) {
                withContext(Dispatchers.IO) { container.predictionLearning.forContexts(contexts) }
            } else emptyList()
            val predictions = predictionEngine.suggestionsForTokens(
                combined,
                learned,
                DictionaryPackManager(this@WeikeInputMethodService).hasEnhancedDictionary()
            )
            if (requestId != predictionRequestId || connection !== currentInputConnection || sensitiveField ||
                pinyinRawBuffer.isNotBlank() || englishBuffer.isNotBlank()
            ) return@trySend
            predictionCandidates = predictions
            render()
        }
    }

    private fun clearPredictions(clearContext: Boolean = false) {
        predictionRequestId += 1
        predictionCandidates = emptyList()
        if (clearContext) predictionContextTokens = emptyList()
        render()
    }

    private fun enqueuePinyin(block: suspend () -> Unit) {
        pinyinOperations.trySend(block)
    }

    private fun flushPendingPinyinBatch() {
        if (!pinyinBatchScheduled && pendingPinyinLetters.isEmpty()) return
        pinyinBatchScheduled = false
        val batch = pendingPinyinLetters.toString()
        pendingPinyinLetters.clear()
        if (batch.isBlank()) return
        enqueuePinyin {
            try {
                // Rime may commit a completed segment while accepting the next
                // batch. Dropping that commit makes correctly typed text vanish.
                applyPinyinState(pinyinDecoder.inputBatch(batch), allowCommit = true)
            } finally {
                pendingPinyinMutations = (pendingPinyinMutations - batch.length).coerceAtLeast(0)
            }
        }
    }

    private fun enqueueAfterPendingPinyin(action: suspend () -> Unit) {
        flushPendingPinyinBatch()
        enqueuePinyin(action)
    }

    private fun clearTextCompositions() {
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        englishBuffer = ""
        englishCandidates = emptyList()
        clearPredictions(clearContext = true)
        enqueuePinyin { pinyinDecoder.clear() }
    }

    private fun captureInputViewRecovery() {
        if (sensitiveField) return
        val recovery = InputViewRecovery(
            packageName = lastPackageName.orEmpty(),
            inputType = currentInputEditorInfo?.inputType ?: 0,
            fieldId = currentInputEditorInfo?.fieldId ?: 0,
            mode = mode,
            modeBeforeSymbols = modeBeforeSymbols,
            pinyinCode = pinyinRawBuffer,
            englishCode = englishBuffer,
            expiresAtMs = SystemClock.elapsedRealtime() + INPUT_VIEW_RECOVERY_WINDOW_MS
        )
        inputViewRecovery = recovery
        Log.d(TAG, "Captured IME recovery; mode=${recovery.mode}; pinyin=${recovery.pinyinCode.length}; field=${recovery.fieldId}")
        serviceScope.launch {
            delay(INPUT_VIEW_RECOVERY_WINDOW_MS)
            if (inputViewRecovery === recovery) inputViewRecovery = null
        }
    }

    private fun restoreInputViewRecovery(recovery: InputViewRecovery) {
        Log.d(TAG, "Restoring IME recovery; mode=${recovery.mode}; pinyin=${recovery.pinyinCode.length}; field=${recovery.fieldId}")
        mode = recovery.mode
        modeBeforeSymbols = recovery.modeBeforeSymbols
        rememberKeyboardMode(mode)
        pinyinBuffer = ""
        pinyinRawBuffer = ""
        pinyinCandidates = emptyList()
        englishBuffer = recovery.englishCode
        englishCandidates = EnglishCandidateEngine.candidates(englishBuffer)
        if (recovery.pinyinCode.isNotBlank() && mode == KeyboardMode.PINYIN) {
            requestPinyinEngine()
            enqueuePinyin {
                applyPinyinState(pinyinDecoder.restoreComposition(recovery.pinyinCode), allowCommit = false)
            }
        } else {
            updateComposingText(englishBuffer)
            render()
        }
    }

    /**
     * Xiaomi can occasionally recreate just the IME service while rotating. Keep
     * the selected surface, but never text, audio, candidates, or cloud settings.
     */
    private fun rememberKeyboardMode(value: KeyboardMode) {
        val retained = if (value == KeyboardMode.SYMBOLS) modeBeforeSymbols else value
        retainedKeyboardMode = retained
        getSharedPreferences(IME_STATE_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_RETAINED_KEYBOARD_MODE, retained.name)
            .apply()
    }

    private fun loadRetainedKeyboardMode(): KeyboardMode? = runCatching {
        getSharedPreferences(IME_STATE_PREFERENCES, MODE_PRIVATE)
            .getString(KEY_RETAINED_KEYBOARD_MODE, null)
            ?.let(KeyboardMode::valueOf)
    }.getOrNull()

    private fun resolveKeyboardTheme(preference: KeyboardTheme): KeyboardTheme = when (preference) {
        KeyboardTheme.SYSTEM -> {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) KeyboardTheme.DARK else KeyboardTheme.LIGHT
        }
        else -> preference
    }

    private fun recordEnglishSelection(value: String) {
        if (value.isBlank()) return
        val entry = EnglishCandidateEngine.selected(value)
        serviceScope.launch { container.englishLearning.upsert(entry) }
    }

    private fun persistDictation(type: InputHistoryType, text: String, durationMs: Long) {
        if (text.isBlank()) return
        serviceScope.launch(Dispatchers.IO) {
            container.usageStats.record(durationMs, countInputUnits(text).toLong())
            persistHistory(type, text)
        }
    }

    private fun persistHistory(type: InputHistoryType, content: String, question: String = "") {
        if (content.isBlank()) return
        serviceScope.launch(Dispatchers.IO) {
            val retention = container.settings.historyRetention()
            if (retention == HistoryRetention.NEVER) return@launch
            pruneHistory(retention)
            container.inputHistory.insert(
                InputHistory(type = type.name, content = content.trim(), question = question.trim())
            )
        }
    }

    private suspend fun pruneHistory(retention: HistoryRetention) {
        when (val duration = retention.durationMs) {
            0L -> container.inputHistory.deleteAll()
            null -> Unit
            else -> container.inputHistory.deleteBefore(System.currentTimeMillis() - duration)
        }
    }

    /** Chinese characters count individually; a contiguous English word or number counts once. */
    private fun countInputUnits(text: String): Int = INPUT_UNIT_PATTERN.findAll(text).count()

    /** Keeps the raw composing code in the focused editor, not in the candidate strip. */
    private fun updateComposingText(value: String) {
        if (mode == KeyboardMode.VOICE) return
        val connection = currentInputConnection ?: return
        if (value.isBlank()) {
            // finishComposingText confirms the final raw character in many editors.
            // Replace it first so the last deleted Pinyin initial cannot leak through.
            connection.setComposingText("", 1)
            connection.finishComposingText()
        } else connection.setComposingText(value, 1)
    }

    private fun insertPolishDraft(value: String) {
        if (value.isEmpty()) return
        val edit = DraftEditor.insert(polishDraft, polishCursor, value)
        polishDraft = edit.text
        polishCursor = edit.cursor
    }

    private fun clearPinyinLearning() {
        serviceScope.launch {
            val cleared = runCatching { pinyinMutex.withLock { pinyinDecoder.clearUserLearning() } }
                .onFailure { Log.e(TAG, "Unable to clear local Pinyin learning data", it) }
                .getOrDefault(false)
            if (cleared) {
                lastPinyinLearning = emptyList()
                serviceScope.launch(Dispatchers.IO) { container.pinyinLearning.deleteAll() }
            }
            render()
            toast(if (cleared) "已清除本机候选学习数据" else "清除学习数据失败")
        }
    }

    private fun clearPredictionLearning() {
        serviceScope.launch(Dispatchers.IO) {
            container.predictionLearning.deleteAll()
        }
        clearPredictions(clearContext = true)
        toast("已清除本机联想学习数据")
    }

    private fun reloadPinyinBundle() {
        serviceScope.launch {
            engineLoadJob?.cancel()
            pinyinMutex.withLock {
                pinyinDecoder.shutdown()
                pinyinReady = false
                pinyinBuffer = ""
                pinyinRawBuffer = ""
                pinyinCandidates = emptyList()
                updateComposingText("")
            }
            requestPinyinEngine()
            render()
        }
    }

    private fun isSensitive(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val passwordVariation = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return passwordVariation || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toastFor(message: String, durationMs: Long) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.show()
        Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, durationMs)
    }

    companion object {
        private const val TAG = "WeikeIme"
        @Volatile private var retainedKeyboardMode: KeyboardMode? = null
        private const val MINIMUM_RECORDING_MS = 700L
        private const val MINIMUM_POLISHED_RECORDING_MS = 350L
        private const val ASR_TIMEOUT_MS = 20_000L
        private const val FAST_POLISHING_TIMEOUT_MS = 3_500L
        private const val TRANSLATION_TIMEOUT_MS = 8_000L
        private const val DEEP_POLISHING_TIMEOUT_MS = 8_000L
        private const val ANSWER_PROCESSING_TIMEOUT_MS = 18_000L
        private const val MAX_WORKFLOW_CONTEXT_CHARS = 8_192
        private const val MAX_DELETE_CONTEXT_CHARS = 64
        private const val PROCESSING_LABEL_FADE_MS = 150L
        private const val TYPEWRITER_CHARACTER_DELAY_MS = 16L
        private const val LANGUAGE_ENGINE_IDLE_TIMEOUT_MS = 5L * 60L * 1000L
        private const val DEFERRED_TERM_SYNC_DELAY_MS = 1_000L
        private const val MAX_PCM_BYTES = AudioRecorder.MAX_RECORDING_SECONDS * AudioRecorder.SAMPLE_RATE * 2
        private val INPUT_UNIT_PATTERN = Regex("[\\u4E00-\\u9FFF]|[A-Za-z]+(?:['-][A-Za-z]+)*|\\d+(?:[.,]\\d+)?")
        private const val MAX_CLIPBOARD_CONTENT_LENGTH = 4_096
        private const val PREDICTION_CONTEXT_LIMIT = 16
        private const val INPUT_VIEW_RECOVERY_WINDOW_MS = 5_000L
        private const val DOUBLE_SPACE_PERIOD_WINDOW_MS = 350L
        private const val AUTO_PUNCTUATION_WINDOW_MS = 10_000L
        private const val RECENT_CLIPBOARD_WINDOW_MS = 15_000L
        private const val QUICK_PASTE_VISIBLE_WINDOW_MS = 5_000L
        private const val DICTATION_PARTIAL_INTERVAL_MS = 1_400L
        private val AUTO_CAPITALIZE_AFTER = setOf('.', '!', '?', '。', '！', '？', '\n')
        private const val LANDSCAPE_OVERLAY_HEIGHT_DP = 258
        private const val IME_STATE_PREFERENCES = "ime_view_state"
        private const val KEY_RETAINED_KEYBOARD_MODE = "retained_keyboard_mode"
        private val SENSITIVE_CLIPBOARD_MARKERS = Regex(
            "(?i)(password|passwd|passcode|验证码|动态码|verification\\s*code|one[- ]?time\\s*password|otp)"
        )
        const val ACTION_CLEAR_RIME_LEARNING = "com.weike.ime.action.CLEAR_RIME_LEARNING"
        const val ACTION_CLEAR_PREDICTION_LEARNING = "com.weike.ime.action.CLEAR_PREDICTION_LEARNING"
        const val ACTION_RELOAD_RIME_BUNDLE = "com.weike.ime.action.RELOAD_RIME_BUNDLE"
        const val ACTION_IMAGE_PICKED = "com.weike.ime.action.IMAGE_PICKED"

        /** 相册选图中转页与 IME 同进程，直接经静态变量交接 URI；
         * 广播仅作门铃（不带 data，避开 IntentFilter 数据匹配的兼容差异）。 */
        @Volatile
        var pendingImageUri: android.net.Uri? = null
    }

    /** Kept only in memory and only long enough to bridge an IME view recreation. */
    private data class InputViewRecovery(
        val packageName: String,
        val inputType: Int,
        val fieldId: Int,
        val mode: KeyboardMode,
        val modeBeforeSymbols: KeyboardMode,
        val pinyinCode: String,
        val englishCode: String,
        val expiresAtMs: Long
    ) {
        fun matches(info: EditorInfo?, sensitive: Boolean): Boolean =
            !sensitive &&
                packageName == info?.packageName.orEmpty() &&
                // HyperOS creates a short-lived placeholder connection during
                // rotation (field -1 / input type 0), then immediately replaces
                // it with the actual editor (typically field 0). Treat unknown
                // values as wildcards, while still rejecting another package.
                (inputType == 0 || info?.inputType == 0 || inputType == info?.inputType) &&
                (fieldId <= 0 || (info?.fieldId ?: 0) <= 0 || fieldId == info?.fieldId)
    }
}
