package com.weike.ime.ime

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.OverScroller
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.weike.ime.R
import com.weike.ime.data.ClipboardEntry
import com.weike.ime.data.HapticStrength
import com.weike.ime.data.KeyboardTheme
import com.weike.ime.data.KeyboardLogoConfig
import com.weike.ime.data.KeyboardLogoStyle
import com.weike.ime.data.VoiceUiState
import com.weike.ime.data.WritingStyle

enum class KeyboardMode { VOICE, ASK, TEXT, ENGLISH, PINYIN, CLIPBOARD, SYMBOLS }

interface KeyboardActions {
    fun selectMode(mode: KeyboardMode)
    fun returnToNineKeyPinyin()
    fun toggleVoice()
    fun toggleDictation()
    /** 长按空格等推杆式入口：按下开始，松开由 [finishDictation] 结束。 */
    fun startDictation()
    fun finishDictation()
    fun startPolishedVoice()
    fun setLongPressTranslation(selected: Boolean)
    fun finishVoice()
    fun cancelVoice()
    fun closeKeyboard()
    fun switchInputMethod()
    fun dismissAnswer()
    fun insertAnswer()
    fun pasteClipboard(entry: ClipboardEntry)
    fun pasteRecentClipboard()
    fun analyzeClipboardReply()
    fun screenshotReply()
    fun selectReplyOption(text: String)
    fun regenerateReplyOptions()
    fun dismissReplyOptions()
    fun polishCurrentText()
    fun selectPolishOption(text: String)
    fun dismissPolishOptions()
    fun setPolishRelation(relation: String)
    fun setPolishCursor(index: Int)
    fun clearComposition()
    /** Clears next-word predictions from the candidate bar. */
    fun dismissPredictions()
    fun openAiChat()
    /** Copies the current selection, or the whole field when nothing is selected. */
    fun copySelection()
    fun pasteClipboardText()
    fun selectAllText()
    fun deleteClipboard(entry: ClipboardEntry)
    fun undoLastInsert()
    fun typeEnglish(value: String)
    fun typeEnglishLetter(value: String)
    fun typePinyin(value: String)
    fun chooseCandidate(candidate: PinyinCandidate)
    fun choosePinyinSpelling(spelling: String)
    fun chooseEnglishCandidate(value: String)
    fun choosePrediction(candidate: PredictionCandidate)
    fun commitEnglishComposition(addSpace: Boolean)
    /** Nine-key "，。?!" key: commits the leading comma and opens the strip. */
    fun punctuationKeyTapped()
    /** Strip pick: replaces the auto-inserted comma when one is pending. */
    fun choosePunctuation(symbol: String)
    fun pressTextSpace(pinyin: Boolean)
    fun backspace()
    fun moveCursorBy(delta: Int)
    fun canBackspace(): Boolean
    fun enter()
    fun newline()
    fun insertAt()
    fun toggleSymbols()
}

/** Custom fixed-layout IME surface based on the supplied phone reference. */
class WeikeKeyboardView(
    context: Context,
    private val actions: KeyboardActions,
    private val keySound: KeyboardSoundPlayer
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val targets = mutableListOf<TouchTarget>()
    private val iconCache = android.util.SparseArray<android.graphics.drawable.Drawable>()
    private val brandLogo = BitmapFactory.decodeResource(resources, R.drawable.logo_lianai_app)

    private var mode = KeyboardMode.PINYIN
    private var availableModes: List<KeyboardMode> = listOf(KeyboardMode.TEXT, KeyboardMode.ASK, KeyboardMode.VOICE)
    private var voiceState: VoiceUiState = VoiceUiState.Idle
    private var pinyinBuffer = ""
    private var pinyinCandidates: List<PinyinCandidate> = emptyList()
    private var spellingOptions: List<String> = emptyList()
    private var selectedSpelling = ""
    private var spellingScroll = 0f
    private var spellingMaxScroll = 0f
    private var spellingTouch = false
    private var spellingDownX = 0f
    private var spellingStartScroll = 0f
    private var englishBuffer = ""
    private var englishCandidates: List<PinyinCandidate> = emptyList()
    private var predictionCandidates: List<PredictionCandidate> = emptyList()
    private var clipboardEntries: List<ClipboardEntry> = emptyList()
    private var recentClipboardText = ""
    private var pinyinReady = true
    private var pinyinStatus = ""
    private var pinyinNineKey = false
    private var nineKeyNumberPad = false
    private var symbolsUseEnglish = false
    private var nineKeySymbols: List<String> = emptyList()
    private var candidateScrollX = 0f
    private var candidateMaxScroll = 0f
    private var candidateDragging = false
    private var candidateDragStartScroll = 0f
    private var candidateStrip: RectF? = null
    private var polishRelationViewport: RectF? = null
    private var polishRelationScrollX = 0f
    private var polishRelationMaxScroll = 0f
    private var polishRelationDragging = false
    private var polishRelationDragStartScroll = 0f
    private val candidateScroller = OverScroller(context)
    private var candidateVelocityTracker: VelocityTracker? = null
    private var answerScrollY = 0f
    private var answerMaxScroll = 0f
    private var answerDragging = false
    private var answerDragStartScroll = 0f
    private var answerViewport: RectF? = null
    private var clipboardScrollY = 0f
    private var clipboardMaxScroll = 0f
    private var clipboardDragStartScroll = 0f
    private var clipboardViewport: RectF? = null
    private var clipboardTracking = false
    private var clipboardTouchedEntry: ClipboardEntry? = null
    private var clipboardRevealId: Long? = null
    private var clipboardSwipeOffset = 0f
    private var clipboardInitialReveal = false
    private var clipboardRows: List<ClipboardRow> = emptyList()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var uppercase = false
    private var sensitive = false
    private var hapticStrength = HapticStrength.MEDIUM
    private var keyboardSoundVolume = .45f
    private var keyboardTheme = KeyboardTheme.LIGHT
    private var showCloseButton = true
    private var quickImeSwitcherEnabled = false
    private var keyboardLogoConfig = KeyboardLogoConfig()
    private var customDarkLogo: android.graphics.Bitmap? = null
    private var customLightLogo: android.graphics.Bitmap? = null
    private var modeTabs: RectF? = null
    private var modeTabDragging = false
    private var modeTabLastIndex = -1
    private var candidateTextSizeLevel = 0
    private var englishAutoCapitalize = true
    private var englishAutoCapitalizeNext = false
    private var keyboardHeightLevel = 0
    private var keyboardBottomOffsetLevel = 0
    private var punctuationShortcuts = false
    private var cursorSliderEnabled = true
    private var automaticUppercase = false
    private var meter = 0f
    private var dictationActive = false
    /** 后台业务开关：截图识别回复是否开放。 */
    private var screenshotReplyEnabled = false
    private var voiceMorph = 0f
    private var modeIndicator = 0f
    private var pressedBox: RectF? = null
    private var cursorSlider: RectF? = null
    private var cursorSliderDragging = false
    private var cursorSliderLastX = 0f
    private var cursorSliderThumbX = Float.NaN
    private var swipeUpTriggered = false
    private var activeTarget: TouchTarget? = null
    private var activeTargetCancelled = false
    private var primaryPointerId = MotionEvent.INVALID_POINTER_ID
    // All pointers, including the first one, are tracked independently. The old
    // implementation tracked only secondary pointers, so lifting the first finger
    // before the second silently discarded its key.
    private val secondaryTouches = mutableMapOf<Int, TouchTarget>()
    private val cancelledSecondaryTouches = mutableSetOf<Int>()
    private val pressedPointers = mutableMapOf<Int, RectF>()
    private val feedbackPointers = mutableSetOf<Int>()
    private var longPressTriggered = false
    private var longPressTranslationSelected = false
    private var longPressVoiceBox: RectF? = null
    private var voiceSendTarget: RectF? = null
    private var holdOverlayProgress = 0f
    private var repeatAction: (() -> Unit)? = null
    private var repeatHaptic: Int? = null
    private var repeatIntervalMs = 92L
    private var lastKeyboardHapticAtMs = 0L
    private val longPressRunnable = Runnable {
        val target = activeTarget ?: return@Runnable
        val action = target.longPressAction ?: return@Runnable
        if (pressedBox != target.box || candidateDragging || answerDragging || clipboardTracking) return@Runnable
        // A compact voice surface can place the send key close to the recording
        // capsule. The send key always owns long press for newline, never polish.
        if (voiceSendTarget?.contains(touchDownX, touchDownY) == true) {
            longPressTriggered = true
            longPressVoiceBox = null
            actions.newline()
            emitHaptic(HapticFeedbackConstants.GESTURE_END)
            invalidate()
            return@Runnable
        }
        longPressTriggered = true
        longPressTranslationSelected = false
        longPressVoiceBox = target.box
        animateHoldOverlay(show = true)
        emitHaptic(HapticFeedbackConstants.GESTURE_START)
        action.invoke()
        invalidate()
    }
    private val repeatBackspace = object : Runnable {
        override fun run() {
            if (!actions.canBackspace()) {
                repeatAction = null
                repeatHaptic = null
                pressedBox = null
                invalidate()
                return
            }
            repeatAction?.invoke() ?: return
            repeatHaptic?.let(::emitHaptic)
            repeatIntervalMs = (repeatIntervalMs * .86f).toLong().coerceAtLeast(34L)
            postDelayed(this, repeatIntervalMs)
        }
    }
    private var processingStartedAt = 0L
    private var polishingStreamActive = false
    private var polishingStreamStartedAt = 0L
    private var processingTextAlpha = 1f
    private var leavingProcessing = false
    private var completionProgress = 1f
    private var shakeOffset = 0f
    private var quickPasteProgress = 0f
    private var quickPasteRenderText = ""
    private var quickPasteMotionStartedAt = 0L
    private var selectedRelation = 0
    private var polishDraft = ""
    private var polishCursor = 0
    /** Enter key caption, derived from the focused field's IME action. */
    private var enterLabel = "发送"
    private var candidateExpanded = false
    private var emojiPanel = false
    private var kaomojiStripVisible = false

    /** Kaomoji quick bar shown by the nine-key "^_^" key, iOS style. */
    private val kaomoji = listOf("^_^", "^o^", "^3^", ">_<", "T_T", "QAQ", "Orz", "♪", "♡")
    private var punctuationStripVisible = false
    /** Active emoji category index and page inside the emoji panel. */
    private var emojiCategory = 0
    private var emojiPage = 0
    /** Symbols page: 0 = numbers & basic punctuation, 1 = CJK brackets & extras. */
    private var symbolsPage = 0
    /** English shift: single tap = one-shot uppercase, double tap = caps lock. */
    private var capsLock = false
    private var lastShiftTapAt = 0L
    /** Language of the keyboard embedded inside the Super Say panel. */
    private var superSayPinyin = true
    private var polishOptions: List<String> = emptyList()
    private var polishLoading = false
    private var replyOptions: List<String> = emptyList()
    private var replyLoading = false
    private var replyEmotion = ""
    // onDraw runs on every keystroke; precompute single-character labels so a
    // frame does not allocate a new String per key.
    private val lowerLabels: Array<String> = Array(128) { it.toChar().toString() }
    private val upperLabels: Array<String> = Array(128) { it.toChar().uppercaseChar().toString() }
    private val whitespaceRegex = Regex("\\s+")
    private var toolsExpanded = false

    private fun collapseTools() {
        toolsExpanded = false
        invalidate()
    }
    private val polishRelations = listOf("通用", "心动对象", "恋人", "朋友", "客户", "上司", "同事", "家人")
    private val quickPasteMotionDurationMs = 5_000L
    private val transition = ValueAnimator().apply {
        duration = 200L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { voiceMorph = it.animatedValue as Float; invalidate() }
    }
    private val modeTransition = ValueAnimator().apply {
        duration = 200L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            modeIndicator = it.animatedValue as Float
            invalidate()
        }
    }
    private val processingFade = ValueAnimator().apply {
        duration = 150L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { processingTextAlpha = it.animatedValue as Float; invalidate() }
    }
    private val quickPasteTransition = ValueAnimator().apply {
        duration = 180L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { quickPasteProgress = it.animatedValue as Float; invalidate() }
    }

    private val logo: android.graphics.Bitmap get() = when (keyboardLogoConfig.style) {
        KeyboardLogoStyle.CUSTOM -> if (keyboardTheme == KeyboardTheme.DARK) customDarkLogo ?: brandLogo else customLightLogo ?: brandLogo
        KeyboardLogoStyle.OPENLESS, KeyboardLogoStyle.VERTICK -> brandLogo
    }
    private val background: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(48, 38, 43) else Color.rgb(255, 249, 250)
    private val key: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(78, 62, 69) else Color.WHITE
    private val specialKey: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(105, 82, 91) else Color.rgb(208, 194, 202)
    private val pressedKey: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(255, 107, 157) else Color.rgb(255, 232, 238)
    private val tabBackground: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(58, 46, 51) else Color.argb(190, 255, 255, 255)
    private val white: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(255, 249, 250) else Color.rgb(45, 52, 54)
    private val muted: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(218, 196, 204) else Color.rgb(153, 153, 153)
    private val accent: Int get() = Color.rgb(255, 107, 157)
    private val actionIcon: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.BLACK else Color.WHITE
    private val cardBg: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(58, 46, 51) else Color.WHITE
    private val cardSoftBg: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(66, 52, 58) else Color.rgb(255, 241, 245)
    private val cardOptionBg: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(66, 52, 58) else Color.rgb(255, 251, 252)
    private val cardStroke: Int get() = if (keyboardTheme == KeyboardTheme.DARK) Color.rgb(105, 82, 91) else Color.rgb(255, 220, 230)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        isClickable = true
        contentDescription = "AI 输入法"
    }

    /** A freshly focused editor must never reopen onto the emoji grid or a
     *  stale strip; typing straight into the leftover emoji panel commits a
     *  row of hearts instead of letters. */
    fun resetTransientPanels() {
        emojiPanel = false
        punctuationStripVisible = false
        kaomojiStripVisible = false
        candidateExpanded = false
        toolsExpanded = false
        spellingScroll = 0f
        invalidate()
    }

    fun update(
        mode: KeyboardMode = this.mode,
        voiceState: VoiceUiState = this.voiceState,
        style: WritingStyle = WritingStyle.CHAT,
        pinyinBuffer: String = this.pinyinBuffer,
        pinyinCandidates: List<PinyinCandidate> = this.pinyinCandidates,
        englishBuffer: String = this.englishBuffer,
        englishCandidates: List<PinyinCandidate> = this.englishCandidates,
        predictionCandidates: List<PredictionCandidate> = this.predictionCandidates,
        liveTranscript: String = "",
        sensitive: Boolean = this.sensitive,
        hapticStrength: HapticStrength = this.hapticStrength,
        pinyinReady: Boolean = this.pinyinReady,
        pinyinStatus: String = this.pinyinStatus,
        keyboardTheme: KeyboardTheme = this.keyboardTheme,
        keyboardSoundVolume: Float = this.keyboardSoundVolume,
        modeOptions: List<KeyboardMode> = this.availableModes,
        clipboardEntries: List<ClipboardEntry> = this.clipboardEntries,
        pinyinNineKey: Boolean = this.pinyinNineKey,
        symbolsUseEnglish: Boolean = this.symbolsUseEnglish,
        nineKeySymbols: List<String> = this.nineKeySymbols,
        showCloseButton: Boolean = this.showCloseButton,
        quickImeSwitcherEnabled: Boolean = this.quickImeSwitcherEnabled,
        candidateTextSizeLevel: Int = this.candidateTextSizeLevel,
        englishAutoCapitalize: Boolean = this.englishAutoCapitalize,
        englishAutoCapitalizeNext: Boolean = this.englishAutoCapitalizeNext,
        keyboardLogo: KeyboardLogoConfig = this.keyboardLogoConfig,
        recentClipboardText: String = this.recentClipboardText,
        keyboardHeightLevel: Int = this.keyboardHeightLevel,
        keyboardBottomOffsetLevel: Int = this.keyboardBottomOffsetLevel,
        punctuationShortcuts: Boolean = this.punctuationShortcuts,
        cursorSliderEnabled: Boolean = this.cursorSliderEnabled,
        polishDraft: String = this.polishDraft,
        polishCursor: Int = this.polishCursor,
        polishOptions: List<String> = this.polishOptions,
        polishLoading: Boolean = this.polishLoading,
        replyOptions: List<String> = this.replyOptions,
        replyLoading: Boolean = this.replyLoading,
        replyEmotion: String = this.replyEmotion,
        enterLabel: String = this.enterLabel,
        dictationActive: Boolean = this.dictationActive,
        screenshotReplyEnabled: Boolean = this.screenshotReplyEnabled,
        spellingOptions: List<String> = this.spellingOptions,
        selectedSpelling: String = this.selectedSpelling
    ) {
        val oldSpellingVisible = showSpellingRow()
        if (this.spellingOptions != spellingOptions) spellingScroll = 0f
        this.spellingOptions = spellingOptions
        this.selectedSpelling = selectedSpelling
        this.dictationActive = dictationActive
        this.screenshotReplyEnabled = screenshotReplyEnabled
        val normalizedModes = modeOptions.distinct().filter { it != KeyboardMode.SYMBOLS }
            .ifEmpty { listOf(KeyboardMode.PINYIN) }
        // Symbols is a temporary sub-page of the text keyboard, not a top-right mode.
        // Keep it intact instead of falling back to the first configured mode (usually voice).
        val hasTextMode = KeyboardMode.TEXT in normalizedModes
        val requestedMode = when {
            mode == KeyboardMode.SYMBOLS -> KeyboardMode.SYMBOLS
            mode in listOf(KeyboardMode.PINYIN, KeyboardMode.ENGLISH) && hasTextMode -> mode
            mode in normalizedModes -> mode
            else -> normalizedModes.first()
        }
        val targetMode = if (sensitive && (requestedMode == KeyboardMode.VOICE || requestedMode == KeyboardMode.ASK)) {
            if (hasTextMode) KeyboardMode.ENGLISH else normalizedModes.first()
        } else requestedMode
        val modeChanged = this.mode != targetMode
        val optionsChanged = this.availableModes != normalizedModes
        val targetIndicator = topModeFor(targetMode, normalizedModes).let(normalizedModes::indexOf).coerceAtLeast(0).toFloat()
        if (modeChanged) requestLayout()
        val previousState = this.voiceState
        val oldActive = isActive(previousState)
        this.mode = targetMode
        this.availableModes = normalizedModes
        this.voiceState = voiceState
        if (this.pinyinBuffer != pinyinBuffer || this.englishBuffer != englishBuffer ||
            this.predictionCandidates != predictionCandidates
        ) {
            candidateScrollX = 0f
            candidateMaxScroll = 0f
            if (pinyinBuffer.isBlank() && englishBuffer.isBlank()) candidateExpanded = false
        }
        val previousPreview = previousState as? VoiceUiState.Preview
        val nextPreview = voiceState as? VoiceUiState.Preview
        if ((previousPreview == null && nextPreview != null) ||
            (previousPreview != null && nextPreview != null && previousPreview.question != nextPreview.question)
        ) {
            answerScrollY = 0f
            answerMaxScroll = 0f
        }
        this.pinyinBuffer = pinyinBuffer
        if (oldSpellingVisible != showSpellingRow()) requestLayout()
        this.pinyinCandidates = pinyinCandidates
        this.englishBuffer = englishBuffer
        this.englishCandidates = englishCandidates
        this.predictionCandidates = predictionCandidates
        if (this.clipboardEntries != clipboardEntries) {
            clipboardScrollY = 0f
            clipboardRevealId = clipboardRevealId?.takeIf { id -> clipboardEntries.any { it.id == id } }
        }
        this.clipboardEntries = clipboardEntries
        this.pinyinReady = pinyinReady
        this.pinyinStatus = pinyinStatus
        this.pinyinNineKey = pinyinNineKey
        this.symbolsUseEnglish = symbolsUseEnglish
        this.enterLabel = enterLabel
        if (targetMode != KeyboardMode.ENGLISH) capsLock = false
        this.nineKeySymbols = nineKeySymbols
        this.keyboardTheme = keyboardTheme
        this.showCloseButton = showCloseButton
        this.quickImeSwitcherEnabled = quickImeSwitcherEnabled
        if (this.keyboardLogoConfig != keyboardLogo) {
            this.keyboardLogoConfig = keyboardLogo
            customDarkLogo = decodeLogo(keyboardLogo.darkPath)
            customLightLogo = decodeLogo(keyboardLogo.lightPath)
        }
        this.candidateTextSizeLevel = candidateTextSizeLevel.coerceIn(-3, 3)
        this.englishAutoCapitalize = englishAutoCapitalize
        this.englishAutoCapitalizeNext = englishAutoCapitalizeNext
        val geometryChanged = this.keyboardHeightLevel != keyboardHeightLevel ||
            this.keyboardBottomOffsetLevel != keyboardBottomOffsetLevel
        this.keyboardHeightLevel = keyboardHeightLevel.coerceIn(-2, 2)
        this.keyboardBottomOffsetLevel = keyboardBottomOffsetLevel.coerceIn(0, 4)
        this.punctuationShortcuts = punctuationShortcuts
        this.cursorSliderEnabled = cursorSliderEnabled
        this.polishDraft = polishDraft
        this.polishCursor = polishCursor.coerceIn(0, polishDraft.length)
        this.polishOptions = polishOptions
        this.polishLoading = polishLoading
        this.replyOptions = replyOptions
        this.replyLoading = replyLoading
        this.replyEmotion = replyEmotion
        if (this.recentClipboardText != recentClipboardText) {
            val showing = recentClipboardText.isNotBlank()
            if (showing) {
                quickPasteRenderText = recentClipboardText
                quickPasteMotionStartedAt = SystemClock.elapsedRealtime()
            }
            quickPasteTransition.cancel()
            quickPasteTransition.setFloatValues(quickPasteProgress, if (showing) 1f else 0f)
            quickPasteTransition.start()
            if (!showing) postDelayed({
                if (this.recentClipboardText.isBlank() && quickPasteProgress <= .01f) quickPasteRenderText = ""
            }, 190L)
        }
        this.recentClipboardText = recentClipboardText
        if (targetMode == KeyboardMode.ENGLISH && englishBuffer.isBlank() && englishAutoCapitalize && englishAutoCapitalizeNext) {
            if (!uppercase) {
                uppercase = true
                automaticUppercase = true
            }
        } else if (automaticUppercase && (!englishAutoCapitalize || !englishAutoCapitalizeNext || englishBuffer.isNotBlank())) {
            uppercase = false
            automaticUppercase = false
        }
        this.sensitive = sensitive
        this.hapticStrength = hapticStrength
        this.keyboardSoundVolume = keyboardSoundVolume
        if (voiceState == VoiceUiState.Processing && previousState != VoiceUiState.Processing) {
            processingStartedAt = System.currentTimeMillis()
            polishingStreamActive = false
            polishingStreamStartedAt = processingStartedAt
            processingTextAlpha = 1f
            leavingProcessing = false
        } else if (previousState == VoiceUiState.Processing && voiceState != VoiceUiState.Processing) {
            leavingProcessing = true
            completionProgress = 0f
            processingFade.cancel()
            processingFade.setFloatValues(1f, 0f)
            processingFade.start()
            postDelayed({
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 200L
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        completionProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }, 150L)
            postDelayed({ leavingProcessing = false; completionProgress = 1f; invalidate() }, 350L)
        }
        val newActive = isActive(voiceState)
        if (oldActive != newActive) {
            transition.cancel()
            transition.setFloatValues(voiceMorph, if (newActive) 1f else 0f)
            transition.start()
        } else invalidate()
        if (modeChanged || optionsChanged) {
            emitHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            modeTransition.cancel()
            modeTransition.setFloatValues(modeIndicator, targetIndicator)
            modeTransition.start()
        }
        if (geometryChanged) requestLayout()
        if (previousState != voiceState) {
            val feedback = when {
                voiceState == VoiceUiState.Listening -> HapticFeedbackConstants.GESTURE_START
                voiceState == VoiceUiState.Processing -> HapticFeedbackConstants.GESTURE_END
                previousState !is VoiceUiState.Preview && voiceState is VoiceUiState.Preview -> HapticFeedbackConstants.CONFIRM
                voiceState is VoiceUiState.Error -> HapticFeedbackConstants.REJECT
                voiceState == VoiceUiState.Idle && previousState == VoiceUiState.Processing -> HapticFeedbackConstants.CONFIRM
                else -> null
            }
            feedback?.let(::emitHaptic)
        }
    }

    fun setAudioLevel(level: Float) {
        val visualLevel = (kotlin.math.ln(1.0 + level.coerceIn(0f, 1f) * 120.0) / kotlin.math.ln(121.0)).toFloat()
        meter = meter * .45f + visualLevel * .55f
        postInvalidateOnAnimation()
    }

    fun setPolishingStreamActive(active: Boolean) {
        polishingStreamActive = active
        if (active) polishingStreamStartedAt = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    fun notePolishingDelta() {
        postInvalidateOnAnimation()
    }

    fun playTimeout() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val phase = it.animatedValue as Float
                shakeOffset = kotlin.math.sin(phase * Math.PI * 6.0).toFloat() * dp(7) * (1f - phase)
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 中文 4 行布局的键高不再固定拉满 368dp：按屏宽推一个接近 1:1.3 的键面，
        // 否则去掉数字行后每行被拉成 35×62dp 的细长条，点起来很别扭。
        val widthPx = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val edgePx = 5f * density
        val letterGapPx = 4f * density
        val rowGapPx = 6f * density
        val letterWidth = (widthPx - edgePx * 2 - letterGapPx * 9) / 10f
        val keyHeight = (letterWidth * 1.32f).coerceIn(42f * density, 54f * density)
        val chineseKeyboardHeight =
            (62f + 8f + 32f) * density + rowGapPx * 3 + keyHeight * 4

        val base = when {
            isLandscape() -> {
                // Scale with the physical screen instead of a fixed 260dp so keys
                // stay comfortably tappable (>40dp) on tall landscape displays.
                val screenH = resources.configuration.screenHeightDp.coerceAtLeast(320)
                dp((screenH * 0.78f).toInt().coerceIn(260, 340))
            }
            // Keep the same key area as the first tab; the draft and relation card adds height above it.
            mode == KeyboardMode.VOICE -> dp(368) + dp(240) - (if (!pinyinReady) dp(70) else dp(62))
            mode == KeyboardMode.ASK -> dp(500)
            mode == KeyboardMode.ENGLISH || mode == KeyboardMode.SYMBOLS -> dp(368)
            else -> chineseKeyboardHeight
        }
        val desired = (base + keyboardInputExtraHeight() + dp(keyboardHeightLevel * 18) + dp(keyboardBottomOffsetLevel * 8)).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        // The floating landscape surface is not attached to a screen edge, so all
        // four corners use the same radius.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        paint.style = Paint.Style.FILL
        paint.color = background
        val corner = dp(24)
        val surface = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                corner,
                corner,
                Path.Direction.CW
            )
        }
        canvas.drawPath(surface, paint)
        targets.clear()
        cursorSlider = null
        candidateStrip = null
        polishRelationViewport = null
        answerViewport = null
        clipboardViewport = null
        clipboardRows = emptyList()
        if (punctuationStripVisible) {
            modeTabs = null
            drawPunctuationStrip(canvas)
        } else if (kaomojiStripVisible) {
            modeTabs = null
            drawKaomojiStrip(canvas)
        } else if (!emojiPanel && mode != KeyboardMode.VOICE && hasComposition() && hasCompositionCandidates()) {
            // Candidate mode replaces the header. Never leave the previous
            // header's mode-selector hit box active over candidate items.
            modeTabs = null
            if (mode == KeyboardMode.PINYIN || mode == KeyboardMode.VOICE) {
                // Candidate calculation is asynchronous. An empty result for a
                // frame is normal and must not flash an internal loading state.
                candidates(canvas, pinyinCandidates, "", onPick = actions::chooseCandidate)
            } else {
                candidates(canvas, englishCandidates, "", onPick = { actions.chooseEnglishCandidate(it.text) })
            }
        } else if (!emojiPanel && !hasComposition() && hasPredictions()) {
            modeTabs = null
            candidates(
                canvas,
                predictionCandidates.map { PinyinCandidate(it.text, it.score) },
                "",
                dismissible = true
            ) { visible -> predictionCandidates.firstOrNull { it.text == visible.text }?.let(actions::choosePrediction) }
        } else {
            drawHeader(canvas)
        }
        if (candidateExpanded && hasComposition() && hasCompositionCandidates()) {
            drawExpandedCandidates(canvas)
            return
        }
        if (emojiPanel && mode in listOf(KeyboardMode.TEXT, KeyboardMode.PINYIN, KeyboardMode.ENGLISH, KeyboardMode.VOICE)) {
            drawEmojiKeyboard(canvas)
            return
        }
        when (mode) {
            KeyboardMode.VOICE, KeyboardMode.ASK -> drawVoice(canvas)
            KeyboardMode.CLIPBOARD -> drawClipboard(canvas)
            KeyboardMode.SYMBOLS -> drawSymbols(canvas)
            KeyboardMode.TEXT -> drawKeyboard(canvas, true)
            else -> drawKeyboard(canvas, mode == KeyboardMode.PINYIN)
        }
        if (cursorSliderEnabled) drawCursorSlider(canvas)
        drawKeyPreview(canvas)
    }

    /**
     * Magnified bubble above the pressed key, the standard "key press preview"
     * affordance. Only shown for targets that opted in via previewLabel.
     */
    private fun drawKeyPreview(canvas: Canvas) {
        val target = activeTarget ?: return
        val preview = target.previewLabel ?: return
        if (activeTargetCancelled || longPressTriggered) return
        val pressed = pressedBox ?: return
        if (!sameBox(pressed, target.box)) return
        val box = target.box
        val bubbleHeight = box.height() + dp(22)
        paint.textSize = sp(28)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val textWidth = paint.measureText(preview)
        val bubbleWidth = maxOf(box.width() + dp(18), textWidth + dp(26))
        val left = (box.centerX() - bubbleWidth / 2f).coerceIn(dp(2), (width - dp(2) - bubbleWidth).coerceAtLeast(dp(2)))
        val top = (box.top - dp(10) - bubbleHeight).coerceAtLeast(dp(2))
        val bubble = RectF(left, top, left + bubbleWidth, top + bubbleHeight)
        rounded(canvas, bubble, dp(12), key)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1)
        paint.color = cardStroke
        canvas.drawRoundRect(bubble, dp(12), dp(12), paint)
        paint.style = Paint.Style.FILL
        paint.color = white
        val baseline = bubble.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(preview, bubble.centerX(), baseline, paint)
    }

    private fun drawHeader(canvas: Canvas) {
        modeTabs = null
        val showingAnswer = mode == KeyboardMode.ASK && voiceState is VoiceUiState.Preview
        if (showingAnswer) {
            val back = RectF(dp(8), dp(10), dp(46), dp(48))
            lucide(canvas, R.drawable.ic_lucide_chevron_left, back.centerX(), back.centerY(), dp(24), white)
            target(back, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.dismissAnswer() }
            // Result insertion replaces the regular header close control, so it
            // must occupy exactly the same visual and touch position.
            val insert = RectF(width - dp(46), dp(10), width - dp(8), dp(48))
            rounded(canvas, insert, dp(19), tabBackground)
            lucide(canvas, R.drawable.ic_lucide_arrow_big_up, insert.centerX(), insert.centerY(), dp(18), muted)
            target(insert, hapticFeedback = HapticFeedbackConstants.CONFIRM) { actions.insertAnswer() }
            return
        } else if (quickPasteRenderText.isNotBlank() && quickPasteProgress > .01f && availableModes.size <= 2) {
            val tabsRight = if (showCloseButton) width - dp(54) else width - dp(8)
            val tabCellWidth = if (isLandscape()) dp(44) else dp(52)
            val tabsLeft = tabsRight - tabCellWidth * availableModes.size
            // Keep the quick paste affordance clear of the mode selector at every mode count.
            val expandedWidth = minOf(dp(220), (tabsLeft - dp(18)).coerceAtLeast(dp(92)))
            val quickPaste = RectF(dp(10), dp(10), dp(10) + expandedWidth * quickPasteProgress, dp(48))
            rounded(canvas, quickPaste, dp(19), tabBackground)
            val iconSurface = RectF(quickPaste.left + dp(4), quickPaste.top + dp(4), quickPaste.left + dp(34), quickPaste.bottom - dp(4))
            rounded(canvas, iconSurface, dp(15), key)
            lucide(canvas, R.drawable.ic_lucide_clipboard_list, iconSurface.centerX(), iconSurface.centerY(), dp(18), white)
            val textBox = RectF(quickPaste.left + dp(40), quickPaste.top, quickPaste.right - dp(8), quickPaste.bottom)
            val content = quickPasteRenderText.replace(whitespaceRegex, " ").trim()
            paint.textSize = sp(13)
            paint.typeface = Typeface.DEFAULT_BOLD
            val textWidth = paint.measureText(content)
            val elapsed = (SystemClock.elapsedRealtime() - quickPasteMotionStartedAt).coerceAtLeast(0L)
            val progress = (elapsed.coerceAtMost(quickPasteMotionDurationMs).toFloat() / quickPasteMotionDurationMs)
            val easedProgress = .5f - .5f * kotlin.math.cos((Math.PI * progress).toFloat())
            // The preview enters from the right and moves left once. Stop as soon
            // as its trailing character reaches the right content edge.
            val textX = textBox.right - textWidth * easedProgress
            canvas.save()
            canvas.clipRect(textBox)
            label(canvas, content, textX, quickPaste.centerY() + dp(5), 13f, white, Paint.Align.LEFT, true)
            canvas.restore()
            if (elapsed < quickPasteMotionDurationMs) postInvalidateOnAnimation()
            target(quickPaste, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.pasteRecentClipboard() }
        }

        // Landscape supplies this control from the floating panel itself.
        // Portrait draws it here in the regular IME surface.
        if (showCloseButton && !isLandscape()) {
            val closeKeyboard = RectF(width - dp(46), dp(10), width - dp(8), dp(48))
            rounded(canvas, closeKeyboard, dp(19), tabBackground)
            lucide(canvas, R.drawable.ic_lucide_chevron_down, closeKeyboard.centerX(), closeKeyboard.centerY(), dp(18), muted)
            target(closeKeyboard, hapticFeedback = HapticFeedbackConstants.GESTURE_END) { actions.closeKeyboard() }
        }

        if (voiceState == VoiceUiState.Listening) {
            // The floating landscape panel owns the far-right close control.
            // Keep the recording cancel action immediately to its left.
            val closeRight = if (showCloseButton) width - dp(54) else width - dp(8)
            val close = RectF(closeRight - dp(38), dp(10), closeRight, dp(48))
            rounded(canvas, close, dp(19), tabBackground)
            lucide(canvas, R.drawable.ic_lucide_x, close.centerX(), close.centerY(), dp(17), muted)
            target(close, hapticFeedback = HapticFeedbackConstants.GESTURE_END) { actions.cancelVoice() }
            return
        }

        // Only a plain keyboard and the AI panel remain. The old mode selector
        // is gone; this row now carries the editing shortcuts the system
        // clipboard menu used to provide, plus a compact AI entry.
        val tabsRight = if (showCloseButton) width - dp(54) else width - dp(8)
        val tabsLeft = dp(8).toFloat()
        val bar = RectF(tabsLeft, dp(10), tabsRight, dp(48))
        rounded(canvas, bar, dp(20), tabBackground)
        var cursor = bar.left + dp(6)
        fun tool(labelText: String, widthDp: Int, action: () -> Unit) {
            val toolWidth = dp(widthDp)
            val box = RectF(cursor, bar.top, cursor + toolWidth, bar.bottom)
            label(
                canvas,
                labelText,
                box.centerX(),
                box.centerY() + dp(5),
                13f,
                muted,
                Paint.Align.CENTER
            )
            target(box, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { action() }
            cursor += toolWidth
        }
        if (!toolsExpanded) {
            tool("工具", 64) {
                toolsExpanded = true
                invalidate()
            }
        } else {
            tool("复制", 54) { actions.copySelection(); collapseTools() }
            tool("粘贴", 54) { actions.pasteClipboardText(); collapseTools() }
            tool("全选", 54) { actions.selectAllText(); collapseTools() }
            tool("收起", 50) { collapseTools() }
        }

        val aiCenterX = bar.right - dp(28)
        val aiPill = RectF(
            aiCenterX - dp(19),
            bar.centerY() - dp(13),
            aiCenterX + dp(19),
            bar.centerY() + dp(13)
        )
        rounded(canvas, aiPill, dp(13), Color.argb(38, 255, 107, 157))
        label(canvas, "AI", aiCenterX, aiPill.centerY() + dp(5), 14f, accent, Paint.Align.CENTER, true)
        target(
            RectF(aiPill),
            !sensitive,
            hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK
        ) { actions.openAiChat() }
    }

    private fun drawClipboard(canvas: Canvas) {
        val left = dp(10)
        val top = dp(65)
        val bottom = contentBottom() - keyboardBottomReserve() - dp(16)
        val viewport = RectF(left, top, width - dp(10), bottom)
        clipboardViewport = RectF(0f, top - dp(6), width.toFloat(), bottom + dp(6))
        if (clipboardEntries.isEmpty()) {
            label(canvas, "剪贴板暂无内容", width / 2f, top + dp(34), 14f, muted, Paint.Align.CENTER)
            return
        }
        val rowHeight = dp(54)
        val gap = dp(8)
        val contentHeight = clipboardEntries.size * (rowHeight + gap) - gap
        clipboardMaxScroll = (contentHeight - viewport.height()).coerceAtLeast(0f)
        clipboardScrollY = clipboardScrollY.coerceIn(0f, clipboardMaxScroll)
        val deleteWidth = dp(68)
        val rows = ArrayList<ClipboardRow>(clipboardEntries.size)
        canvas.save()
        canvas.clipRect(viewport)
        var y = viewport.top - clipboardScrollY
        clipboardEntries.forEach { entry ->
            val base = RectF(viewport.left, y, viewport.right, y + rowHeight)
            rows += ClipboardRow(entry, base)
            if (base.bottom >= viewport.top - rowHeight && base.top <= viewport.bottom + rowHeight) {
                val revealed = clipboardRevealId == entry.id
                val swiping = clipboardTracking && clipboardTouchedEntry?.id == entry.id && clipboardSwipeOffset > 0f
                if (revealed || swiping) {
                    rounded(canvas, RectF(base.right - deleteWidth, base.top, base.right, base.bottom), dp(9), Color.rgb(207, 64, 69))
                    label(canvas, "删除", base.right - deleteWidth / 2, base.centerY() + dp(5), 14f, Color.WHITE, Paint.Align.CENTER, true)
                }
                val offset = when {
                    clipboardTracking && clipboardTouchedEntry?.id == entry.id -> -clipboardSwipeOffset
                    revealed -> -deleteWidth
                    else -> 0f
                }
                val contentBox = RectF(base).apply { offset(offset, 0f) }
                rounded(canvas, contentBox, dp(9), key)
                val content = entry.content.replace(whitespaceRegex, " ").trim()
                leftClippedLabel(canvas, content, contentBox, 16f, white)
            }
            y += rowHeight + gap
        }
        canvas.restore()
        clipboardRows = rows
    }

    private fun drawVoice(canvas: Canvas) {
        voiceSendTarget = null
        val state = voiceState
        if (mode == KeyboardMode.ASK && (replyLoading || replyOptions.isNotEmpty())) {
            drawReplyRecommendations(canvas)
            return
        }
        val answer = (state as? VoiceUiState.Preview)?.takeIf { mode == KeyboardMode.ASK }
        if (answer != null) {
            drawAnswer(canvas, answer.question, answer.text, answer.streaming)
            return
        }
        if (mode == KeyboardMode.ASK && (state == VoiceUiState.Idle || state is VoiceUiState.Error)) {
            drawReplyPrompt(canvas, (state as? VoiceUiState.Error)?.message)
            return
        }
        if (mode == KeyboardMode.VOICE && (state == VoiceUiState.Idle || state is VoiceUiState.Error)) {
            drawPolishPrompt(canvas, (state as? VoiceUiState.Error)?.message)
            return
        }
        val listening = state == VoiceUiState.Listening
        val processing = state == VoiceUiState.Processing
        val cx = width / 2f
        val morph = voiceMorph
        val sizeScale = if (isLandscape()) .78f else 1f
        val buttonWidth = dp((188f * sizeScale).toInt()) - dp((56f * sizeScale).toInt()) * morph
        val buttonHeight = dp((64f * sizeScale).toInt()) + dp((68f * sizeScale).toInt()) * morph
        val cy = if (isLandscape()) height * .48f else dp(174)
        val buttonX = cx + shakeOffset
        val button = RectF(buttonX - buttonWidth / 2, cy - buttonHeight / 2, buttonX + buttonWidth / 2, cy + buttonHeight / 2)
        if (longPressTriggered && longPressVoiceBox != null && mode == KeyboardMode.VOICE) {
            drawLongPressTranslationOverlay(canvas)
            return
        }
        if (processing) {
            rounded(canvas, button, buttonHeight / 2, Color.rgb(120, 120, 123))
            val elapsed = (System.currentTimeMillis() - processingStartedAt).coerceAtLeast(0L)
            streamProgress(canvas, button)
            val pulse = .8f + .2f * ((kotlin.math.sin((elapsed % 1500L) / 1500f * Math.PI * 2.0) + 1.0) / 2.0).toFloat()
            label(
                canvas,
                if (mode == KeyboardMode.ASK) "正在生成回复" else "\u6da6\u8272\u4e2d",
                buttonX,
                cy + dp(6),
                16f,
                Color.rgb(58, 58, 60),
                Paint.Align.CENTER,
                true,
                pulse
            )
            postInvalidateOnAnimation()
        } else {
            val fill = if (leavingProcessing) blend(Color.rgb(120, 120, 123), white, completionProgress) else white
            rounded(canvas, button, buttonHeight / 2, fill)
            val micAlpha = if (leavingProcessing) completionProgress else 1f - morph
            if (micAlpha > .01f) lucide(canvas, R.drawable.ic_lucide_mic, buttonX, cy, dp(30), actionIcon, micAlpha)
            if (listening && morph > .01f) {
                voiceBars(canvas, buttonX, cy, morph)
            }
            if (leavingProcessing) {
                label(
                    canvas,
                    if (mode == KeyboardMode.ASK) "正在生成回复" else "\u6da6\u8272\u4e2d",
                    buttonX,
                    cy + dp(6),
                    16f,
                    Color.rgb(58, 58, 60),
                    Paint.Align.CENTER,
                    true,
                    processingTextAlpha
                )
            }
        }
        val prompt = when {
            listening -> "松开以完成"
            processing -> ""
            state is VoiceUiState.Error -> state.message
            else -> "按下开始说话"
        }
        val resolvedPrompt = if (mode == KeyboardMode.ASK && !listening && !processing && state !is VoiceUiState.Error) {
            "\u6309\u4e0b\u53d1\u51fa\u6307\u4ee4"
        } else prompt
        if (resolvedPrompt.isNotEmpty()) label(canvas, resolvedPrompt, buttonX, cy - buttonHeight / 2 - dp(20), 14f, muted, Paint.Align.CENTER, true)
        if (!processing && !leavingProcessing) {
            if (mode == KeyboardMode.VOICE) {
                target(
                    button,
                    hapticFeedback = null,
                    longPressAction = { actions.startPolishedVoice() },
                    releaseAction = { actions.finishVoice() }
                ) { actions.toggleVoice() }
            } else {
                target(button, hapticFeedback = null) { actions.toggleVoice() }
            }
        }
        if (!listening && mode != KeyboardMode.ASK) {
            val bottom = contentBottom() - keyboardBottomReserve() -
                if (isLandscape()) dp(14) else dp(8)
            val rightX = width - dp(43)
            val leftX = dp(43)
            val lowerY = bottom - dp(24)
            val upperY = lowerY - dp(55)

            // Delete remains in the upper-right. The lower actions adapt to the slider layout.
            circle(canvas, rightX, upperY, dp(24), key)
            lucide(canvas, R.drawable.ic_lucide_delete, rightX, upperY, dp(22), muted)
            target(
                RectF(rightX - dp(25), upperY - dp(25), rightX + dp(25), upperY + dp(25)),
                repeat = true
            ) { actions.backspace() }

            if (cursorSliderEnabled) {
                circle(canvas, rightX, lowerY, dp(24), key)
                lucide(canvas, R.drawable.ic_lucide_move_right, rightX, lowerY, dp(21), muted)
                target(
                    RectF(rightX - dp(25), lowerY - dp(25), rightX + dp(25), lowerY + dp(25)),
                    longPressAction = { actions.newline() }
                ) { actions.enter() }
                voiceSendTarget = RectF(rightX - dp(25), lowerY - dp(25), rightX + dp(25), lowerY + dp(25))

                circle(canvas, leftX, lowerY, dp(24), key)
                lucide(canvas, R.drawable.ic_lucide_at_sign, leftX, lowerY, dp(21), muted)
                target(RectF(leftX - dp(25), lowerY - dp(25), leftX + dp(25), lowerY + dp(25))) {
                    actions.insertAt()
                }
                if (quickImeSwitcherEnabled && mode == KeyboardMode.VOICE) {
                    circle(canvas, leftX, upperY, dp(24), key)
                    lucide(canvas, R.drawable.ic_lucide_keyboard, leftX, upperY, dp(21), muted)
                    target(RectF(leftX - dp(25), upperY - dp(25), leftX + dp(25), upperY + dp(25))) {
                        actions.switchInputMethod()
                    }
                }
            } else {
                val newline = RectF(cx - dp(60), bottom - dp(48), cx + dp(60), bottom)
                rounded(canvas, newline, dp(24), key)
                label(canvas, "\u53d1\u9001", newline.centerX(), newline.centerY() + dp(5), 14f, muted, Paint.Align.CENTER)
                target(newline, longPressAction = { actions.newline() }) { actions.enter() }
                voiceSendTarget = RectF(newline)
                circle(canvas, rightX, lowerY, dp(24), key)
                lucide(canvas, R.drawable.ic_lucide_at_sign, rightX, lowerY, dp(21), muted)
                target(RectF(rightX - dp(25), lowerY - dp(25), rightX + dp(25), lowerY + dp(25))) {
                    actions.insertAt()
                }
                if (quickImeSwitcherEnabled && mode == KeyboardMode.VOICE) {
                    circle(canvas, leftX, lowerY, dp(24), key)
                    lucide(canvas, R.drawable.ic_lucide_keyboard, leftX, lowerY, dp(21), muted)
                    target(RectF(leftX - dp(25), lowerY - dp(25), leftX + dp(25), lowerY + dp(25))) {
                        actions.switchInputMethod()
                    }
                }
            }
        }
    }

    private fun drawLongPressTranslationOverlay(canvas: Canvas) {
        if (holdOverlayProgress <= .01f) return
        val progress = holdOverlayProgress
        val centerX = width / 2f
        val maskTop = height - height * .52f * progress
        val mask = RectF(-dp(32), maskTop, width + dp(32), height + dp(32))
        // Selection is expressed by contrast, not by making the inactive target
        // brighter. Dark mode selects white; light mode selects black.
        val selectedFill = white
        val polishSelected = !longPressTranslationSelected
        val polishColor = if (polishSelected) selectedFill else key
        rounded(canvas, mask, width.toFloat() / 2f, blend(key, polishColor, progress))
        label(
            canvas,
            "松开以润色",
            centerX,
            maskTop + dp(48),
            19f,
            if (polishSelected) actionIcon else white,
            Paint.Align.CENTER,
            true
        )

        val capsuleWidth = width * .66f
        val capsuleHeight = dp(68)
        val capsuleBottom = maskTop - dp(18)
        val capsule = RectF(centerX - capsuleWidth / 2f, capsuleBottom - capsuleHeight, centerX + capsuleWidth / 2f, capsuleBottom)
        val translationColor = if (longPressTranslationSelected) selectedFill else key
        rounded(canvas, capsule, capsuleHeight / 2f, blend(key, translationColor, progress))
        label(
            canvas,
            "英语（美国）",
            centerX,
            capsule.centerY() + dp(7),
            22f,
            if (longPressTranslationSelected) actionIcon else white,
            Paint.Align.CENTER,
            true
        )
        label(canvas, "向上滑动以翻译", centerX, capsule.top - dp(18), 14f, muted, Paint.Align.CENTER, true)
        val closeRight = if (showCloseButton) width - dp(54) else width - dp(8)
        val close = RectF(closeRight - dp(38), dp(10), closeRight, dp(48))
        rounded(canvas, close, dp(19), tabBackground)
        lucide(canvas, R.drawable.ic_lucide_x, close.centerX(), close.centerY(), dp(17), muted)
        target(close, hapticFeedback = HapticFeedbackConstants.GESTURE_END) { actions.cancelVoice() }
        postInvalidateOnAnimation()
    }

    private fun animateHoldOverlay(show: Boolean) {
        ValueAnimator.ofFloat(holdOverlayProgress, if (show) 1f else 0f).apply {
            duration = if (show) 220L else 160L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { holdOverlayProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun drawReplyRecommendations(canvas: Canvas) {
        val card = RectF(dp(5), dp(58), width - dp(5), contentBottom() - dp(10))
        rounded(canvas, card, dp(16), cardBg)
        if (replyLoading) {
            gradientRounded(canvas, RectF(width / 2f - dp(50), card.top + dp(65), width / 2f + dp(50), card.top + dp(165)), dp(50), Color.rgb(255, 137, 184), Color.rgb(255, 151, 126))
            label(canvas, "✨", width / 2f, card.top + dp(126), 32f, Color.WHITE, Paint.Align.CENTER, true)
            label(canvas, "正在分析并生成3条回复...", width / 2f, card.top + dp(210), 16f, accent, Paint.Align.CENTER, true)
            drawReplyClose(canvas, card)
            return
        }
        val emotion = RectF(card.left + dp(14), card.top + dp(14), card.right - dp(54), card.top + dp(66))
        rounded(canvas, emotion, dp(14), cardSoftBg)
        label(canvas, "😊", emotion.left + dp(30), emotion.centerY() + dp(8), 24f, white, Paint.Align.CENTER)
        label(canvas, replyEmotion.ifBlank { "积极期待" }, emotion.left + dp(57), emotion.centerY() + dp(5), 14f, Color.rgb(255, 137, 126), Paint.Align.LEFT, true)
        label(canvas, "AI推荐回复（点击使用）", card.left + dp(14), card.top + dp(91), 12f, accent, Paint.Align.LEFT)
        replyOptions.take(3).forEachIndexed { index, option ->
            val top = card.top + dp(104) + index * dp(75)
            val box = RectF(card.left + dp(14), top, card.right - dp(14), top + dp(63))
            rounded(canvas, box, dp(14), cardOptionBg)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1)
            paint.color = cardStroke
            canvas.drawRoundRect(box, dp(14), dp(14), paint)
            paint.style = Paint.Style.FILL
            leftClippedLabel(canvas, option, RectF(box.left + dp(12), box.top, box.right - dp(12), box.bottom), 13f, white)
            target(box, hapticFeedback = HapticFeedbackConstants.CONFIRM) { actions.selectReplyOption(option) }
        }
        val retry = RectF(card.centerX() - dp(68), card.bottom - dp(45), card.centerX() + dp(68), card.bottom - dp(8))
        label(canvas, "🔄 重新分析", retry.centerX(), retry.centerY() + dp(5), 13f, accent, Paint.Align.CENTER, true)
        target(retry, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.regenerateReplyOptions() }
        drawReplyClose(canvas, card)
    }

    private fun drawReplyClose(canvas: Canvas, card: RectF) {
        val close = RectF(card.right - dp(46), card.top + dp(10), card.right - dp(10), card.top + dp(46))
        rounded(canvas, close, dp(18), cardSoftBg)
        label(canvas, "×", close.centerX(), close.centerY() + dp(6), 22f, accent, Paint.Align.CENTER)
        target(close, hapticFeedback = HapticFeedbackConstants.GESTURE_END) { actions.dismissReplyOptions() }
    }

    private fun drawAnswer(canvas: Canvas, question: String, answer: String, streaming: Boolean) {
        val left = dp(20)
        val right = width - dp(20)
        val viewportTop = dp(76)
        val viewportBottom = contentBottom() - keyboardBottomReserve() - dp(18)
        val viewport = RectF(left, viewportTop, right, viewportBottom)
        answerViewport = RectF(0f, viewportTop - dp(6), width.toFloat(), viewportBottom + dp(6))

        val topPadding = dp(20)
        val lines = buildAnswerLines(question.trim(), answer.trim(), streaming, viewport.width())
        val contentHeight = (topPadding + lines.sumOf { it.height.toDouble() }.toFloat()).coerceAtLeast(viewport.height())
        answerMaxScroll = (contentHeight - viewport.height()).coerceAtLeast(0f)
        answerScrollY = answerScrollY.coerceIn(0f, answerMaxScroll)

        canvas.save()
        canvas.clipRect(viewport)
        var y = viewport.top + topPadding - answerScrollY
        lines.forEach { line ->
            if (y + line.height >= viewport.top - dp(24) && y <= viewport.bottom + dp(24)) {
                label(canvas, line.text, left, y, line.sizeSp, line.color, Paint.Align.LEFT, line.bold, line.alpha)
            }
            y += line.height
        }
        canvas.restore()
    }

    private fun buildAnswerLines(question: String, answer: String, streaming: Boolean, maxWidth: Float): List<AnswerLine> {
        val lines = mutableListOf<AnswerLine>()
        lines += AnswerLine("TA说", 14f, accent, true, 1f, dp(28))
        lines += wrapAnswerText(if (question.isBlank()) "未读取到对方消息" else question, 17f, white, true, maxWidth, dp(27))
        lines += AnswerLine("", 18f, white, false, 1f, dp(18))
        lines += AnswerLine("推荐回复 · 点击右上角使用", 14f, accent, true, 1f, dp(28))
        val answerBody = answer.ifBlank { if (streaming) "" else "暂时没有生成回复" }
        if (answerBody.isNotBlank()) lines += wrapAnswerText(answerBody, 18f, white, false, maxWidth, dp(28))
        return lines
    }

    private fun wrapAnswerText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean,
        maxWidth: Float,
        lineHeight: Float
    ): List<AnswerLine> {
        val result = mutableListOf<AnswerLine>()
        paint.textSize = sp(sizeSp.toInt())
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val paragraphs = text.split('\n')
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            if (paragraph.isEmpty()) {
                result += AnswerLine("", sizeSp, color, bold, 1f, lineHeight)
            } else {
                var remaining = paragraph
                while (remaining.isNotEmpty()) {
                    val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                    result += AnswerLine(remaining.substring(0, count), sizeSp, color, bold, 1f, lineHeight)
                    remaining = remaining.substring(count)
                }
            }
            if (paragraphIndex != paragraphs.lastIndex) {
                // Paragraph spacing is represented by an empty line entry.
            }
        }
        return result
    }

    private fun drawKeyboard(canvas: Canvas, pinyin: Boolean) {
        if (pinyin && pinyinNineKey) {
            drawNineKeyKeyboard(canvas, standardKeyboardTop())
            return
        }
        if (!pinyin) {
            drawEnglishKeyboard(canvas, dp(62))
            return
        }
        val top = standardKeyboardTop()
        val gap = dp(6)
        // 中文输入不显示顶部数字行：四行键位更高更好按，数字从「123」进入。
        val keyHeight = (contentBottom() - top - dp(8) - keyboardBottomReserve() - gap * 3) / 4f
        val edge = dp(5)
        val letterWidth = (width - edge * 2 - dp(4) * 9) / 10f
        var rowTop = top
        letters(canvas, "qwertyuiop", rowTop, keyHeight, pinyin, edge, letterWidth)
        rowTop += keyHeight + gap
        val secondWidth = letterWidth * 9 + dp(4) * 8
        letters(canvas, "asdfghjkl", rowTop, keyHeight, pinyin, (width - secondWidth) / 2f, letterWidth)
        rowTop += keyHeight + gap
        thirdRow(canvas, rowTop, keyHeight, pinyin, letterWidth)
        rowTop += keyHeight + gap
        bottomRow(canvas, rowTop, keyHeight, pinyin, letterWidth)
    }

    private fun drawEnglishKeyboard(canvas: Canvas, top: Float) {
        val gap = dp(7)
        val edge = dp(5)
        val bottomActionHeight = dp(58)
        val keyHeight = (contentBottom() - top - gap - keyboardBottomReserve().coerceAtLeast(dp(30)) - bottomActionHeight - gap * 3) / 4f
        val letterWidth = (width - edge * 2 - gap * 9) / 10f
        letters(canvas, "qwertyuiop", top, keyHeight, false, edge, letterWidth)
        val secondWidth = letterWidth * 9 + gap * 8
        letters(canvas, "asdfghjkl", top + keyHeight + gap, keyHeight, false, (width - secondWidth) / 2f, letterWidth)
        thirdRow(canvas, top + (keyHeight + gap) * 2, keyHeight, false, letterWidth)
        englishBottomRow(canvas, top + (keyHeight + gap) * 3, keyHeight, letterWidth)
        drawQwertyBottomActions(canvas, top + keyHeight * 4 + gap * 4, edge)
    }

    private fun drawKeyboardFrom(canvas: Canvas, top: Float, pinyin: Boolean) {
        if (pinyin && pinyinNineKey) {
            drawNineKeyKeyboard(canvas, top)
            return
        }
        val gap = dp(6)
        // 中文输入用不到顶部数字行：去掉后四行键位更高、更好按，数字仍可从
        // 左下角「123」进入。
        val showNumberRow = !pinyin
        val rowCount = if (showNumberRow) 5 else 4
        val keyHeight = (contentBottom() - top - dp(8) - keyboardBottomReserve() - gap * (rowCount - 1)) / rowCount
        val edge = dp(5)
        val letterWidth = (width - edge * 2 - dp(4) * 9) / 10f
        var rowTop = top
        if (showNumberRow) {
            numberRow(canvas, rowTop, keyHeight, edge, letterWidth)
            rowTop += keyHeight + gap
        }
        letters(canvas, "qwertyuiop", rowTop, keyHeight, pinyin, edge, letterWidth)
        rowTop += keyHeight + gap
        val secondWidth = letterWidth * 9 + dp(4) * 8
        letters(canvas, "asdfghjkl", rowTop, keyHeight, pinyin, (width - secondWidth) / 2f, letterWidth)
        rowTop += keyHeight + gap
        thirdRow(canvas, rowTop, keyHeight, pinyin, letterWidth)
        rowTop += keyHeight + gap
        bottomRow(canvas, rowTop, keyHeight, pinyin, letterWidth)
    }

    private fun drawReplyPrompt(canvas: Canvas, error: String?) {
        val card = RectF(dp(12), dp(66), width - dp(12), contentBottom() - keyboardBottomReserve() - dp(12))
        rounded(canvas, card, dp(16), cardBg)
        label(canvas, "帮你回", card.left + dp(18), card.top + dp(34), 17f, white, Paint.Align.LEFT, true)
        label(canvas, "复制对方的话，AI帮你分析并生成回复", card.left + dp(18), card.top + dp(60), 13f, muted, Paint.Align.LEFT)

        val button = RectF(card.left + dp(16), card.top + dp(82), card.right - dp(16), card.top + dp(132))
        rounded(canvas, button, dp(16), accent)
        label(canvas, "+  粘贴TA的话", button.centerX(), button.centerY() + dp(5), 15f, Color.WHITE, Paint.Align.CENTER, true)
        target(button, hapticFeedback = HapticFeedbackConstants.CONFIRM) { actions.analyzeClipboardReply() }

        if (screenshotReplyEnabled) {
            val shotButton = RectF(card.left + dp(16), button.bottom + dp(14), card.right - dp(16), button.bottom + dp(62))
            rounded(canvas, shotButton, dp(16), cardOptionBg)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1); paint.color = accent
            canvas.drawRoundRect(shotButton, dp(16).toFloat(), dp(16).toFloat(), paint)
            paint.style = Paint.Style.FILL
            label(canvas, "📷  截图识别回复", shotButton.centerX(), shotButton.centerY() + dp(5), 14f, accent, Paint.Align.CENTER, true)
            target(shotButton, hapticFeedback = HapticFeedbackConstants.CONFIRM) { actions.screenshotReply() }

            val tipAreaTop = shotButton.bottom
            val tip = error?.takeIf { it.isNotBlank() } ?: "没有TA的原话？直接截聊天图，AI识别后帮你回"
            val tipColor = if (error.isNullOrBlank()) muted else accent
            label(canvas, tip, card.centerX(), tipAreaTop + dp(28), 12f, tipColor, Paint.Align.CENTER)
        } else {
            val tip = error?.takeIf { it.isNotBlank() } ?: "复制微信或短信中的消息后，点击上方按钮"
            val tipColor = if (error.isNullOrBlank()) muted else accent
            label(canvas, tip, card.centerX(), button.bottom + dp(34), 12f, tipColor, Paint.Align.CENTER)
        }
    }


    private fun drawPolishPrompt(canvas: Canvas, error: String?) {
        if (polishLoading) {
            val card = RectF(dp(5), dp(56), width - dp(5), contentBottom() - dp(10))
            rounded(canvas, card, dp(16), cardBg)
            gradientRounded(canvas, RectF(width / 2f - dp(55), card.top + dp(58), width / 2f + dp(55), card.top + dp(168)), dp(55), Color.rgb(255, 137, 184), Color.rgb(255, 151, 126))
            label(canvas, "✨", width / 2f, card.top + dp(126), 34f, Color.WHITE, Paint.Align.CENTER, true)
            label(canvas, "AI正在优化...", width / 2f, card.top + dp(215), 18f, accent, Paint.Align.CENTER, true)
            label(canvas, "正在连接大模型，请稍候", width / 2f, card.top + dp(245), 13f, Color.rgb(255, 151, 126), Paint.Align.CENTER)
            return
        }
        if (polishOptions.isNotEmpty()) {
            val card = RectF(dp(5), dp(56), width - dp(5), contentBottom() - dp(10))
            rounded(canvas, card, dp(16), cardBg)
            label(canvas, "AI优化话术（点击选择）", card.left + dp(14), card.top + dp(29), 13f, accent, Paint.Align.LEFT)
            val close = RectF(card.right - dp(43), card.top + dp(8), card.right - dp(9), card.top + dp(42))
            label(canvas, "×", close.centerX(), close.centerY() + dp(6), 23f, accent, Paint.Align.CENTER)
            target(close) { actions.dismissPolishOptions() }
            polishOptions.take(3).forEachIndexed { index, option ->
                val top = card.top + dp(52) + index * dp(76)
                val box = RectF(card.left + dp(14), top, card.right - dp(14), top + dp(64))
                rounded(canvas, box, dp(14), cardOptionBg)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1); paint.color = cardStroke
                canvas.drawRoundRect(box, dp(14), dp(14), paint); paint.style = Paint.Style.FILL
                leftClippedLabel(canvas, option, RectF(box.left + dp(12), box.top, box.right - dp(12), box.bottom), 14f, white)
                target(box, hapticFeedback = HapticFeedbackConstants.CONFIRM) { actions.selectPolishOption(option) }
            }
            return
        }
        val card = RectF(dp(6), dp(60), width - dp(6), dp(228))
        rounded(canvas, card, dp(15), cardBg)
        // Super-say owns an internal editor. Render the live Pinyin composition
        // beside confirmed text so typing never appears to vanish before a pick.
        val cursor = polishCursor.coerceIn(0, polishDraft.length)
        val visibleDraft = polishDraft.substring(0, cursor) + pinyinBuffer + polishDraft.substring(cursor)
        val draftText = visibleDraft.ifBlank { error?.takeIf { it.isNotBlank() } ?: "输入你想说的话，AI帮你优化" }
        label(canvas, draftText, card.left + dp(15), card.top + dp(32), 13f, if (visibleDraft.isBlank()) Color.rgb(214, 142, 157) else white, Paint.Align.LEFT)
        val editor = RectF(card.left + dp(12), card.top + dp(8), card.right - dp(12), card.top + dp(48))
        if (visibleDraft.isNotBlank()) {
            paint.textSize = sp(13)
            paint.typeface = Typeface.DEFAULT
            val beforeCursor = polishDraft.substring(0, cursor) + pinyinBuffer
            val cursorX = (card.left + dp(15) + paint.measureText(beforeCursor)).coerceAtMost(editor.right - dp(2))
            paint.color = accent
            paint.strokeWidth = dp(2)
            canvas.drawLine(cursorX, card.top + dp(14), cursorX, card.top + dp(40), paint)
        }
        target(editor, hapticFeedback = HapticFeedbackConstants.TEXT_HANDLE_MOVE) {
            if (pinyinBuffer.isBlank()) actions.setPolishCursor(polishIndexAt(touchDownX, card.left + dp(15)))
        }

        val action = RectF(card.right - dp(82), card.top + dp(55), card.right - dp(12), card.top + dp(87))
        gradientRounded(canvas, action, dp(15), Color.rgb(255, 183, 205), Color.rgb(255, 155, 154))
        label(canvas, "✨ 优化", action.centerX(), action.centerY() + dp(4), 12f, Color.WHITE, Paint.Align.CENTER, true)
        target(action, hapticFeedback = HapticFeedbackConstants.CONFIRM) { actions.polishCurrentText() }

        if (!error.isNullOrBlank() && polishDraft.isNotBlank()) {
            leftClippedLabel(
                canvas,
                error,
                RectF(card.left + dp(15), card.top + dp(88), card.right - dp(15), card.top + dp(112)),
                11f,
                Color.rgb(220, 70, 92)
            )
        }

        label(canvas, "对方是你的", card.left + dp(15), card.top + dp(125), 11f, Color.rgb(214, 142, 157), Paint.Align.LEFT)
        val chipTop = card.top + dp(134)
        val chipGap = dp(7)
        val chipWidth = dp(52)
        val viewport = RectF(card.left + dp(8), chipTop, card.right - dp(8), chipTop + dp(30))
        polishRelationViewport = viewport
        val contentWidth = dp(15) + polishRelations.size * chipWidth + (polishRelations.size - 1) * chipGap + dp(15)
        polishRelationMaxScroll = (contentWidth - viewport.width()).coerceAtLeast(0f)
        polishRelationScrollX = polishRelationScrollX.coerceIn(0f, polishRelationMaxScroll)
        canvas.save()
        canvas.clipRect(viewport)
        polishRelations.forEachIndexed { index, relation ->
            val left = card.left + dp(15) + index * (chipWidth + chipGap) - polishRelationScrollX
            val chip = RectF(left, chipTop, left + chipWidth, chipTop + dp(28))
            if (index == selectedRelation) gradientRounded(canvas, chip, dp(12), Color.rgb(255, 107, 157), Color.rgb(255, 160, 122))
            else {
                rounded(canvas, chip, dp(12), cardSoftBg)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1)
                paint.color = cardStroke
                canvas.drawRoundRect(chip, dp(12), dp(12), paint)
                paint.style = Paint.Style.FILL
            }
            label(canvas, relation, chip.centerX(), chip.centerY() + dp(4), 10f, if (index == selectedRelation) Color.WHITE else accent, Paint.Align.CENTER, index == selectedRelation)
            if (chip.right > viewport.left && chip.left < viewport.right) target(chip, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                selectedRelation = index; actions.setPolishRelation(relation); invalidate()
            }
        }
        canvas.restore()
        if (showSpellingRow()) {
            drawSpellingRow(canvas)
            candidates(canvas, pinyinCandidates, "", onPick = actions::chooseCandidate)
        }
        drawKeyboardFrom(canvas, superSayKeyboardTop(), superSayPinyin)
    }

    private fun numberRow(canvas: Canvas, y: Float, h: Float, inset: Float, keyWidth: Float) {
        val gap = dp(4)
        "1234567890".forEachIndexed { index, character ->
            val x = inset + index * (keyWidth + gap)
            val box = RectF(x, y, x + keyWidth, y + h)
            key(canvas, box)
            centeredLabel(canvas, lowerLabels[character.code], box, 18f, white, Paint.Align.CENTER)
            target(box, keySound = true, previewLabel = lowerLabels[character.code]) { actions.typeEnglish(lowerLabels[character.code]) }
        }
    }

    private fun drawNineKeyKeyboard(canvas: Canvas, top: Float = dp(62)) {
        val gap = dp(7)
        val edge = dp(5)
        // The bottom IME-switch / voice row is a real row, not a floating overlay.
        // Reserve its height before calculating the 4 keypad rows, otherwise the
        // icons visually and tactically collide with emoji/send/space keys.
        val bottomActionHeight = dp(58)
        val bottomReserve = keyboardBottomReserve().coerceAtLeast(dp(30)) + bottomActionHeight
        val keyHeight = (contentBottom() - top - gap - bottomReserve - gap * 3) / 4f
        val keyWidth = (width - edge * 2 - gap * 4) / 5f
        // Derive from specialKey so every modifier/function key shares the same
        // warm rose tint; blending towards `muted` produced a cool grey that
        // clashed with the pink theme.
        val functionKey = blend(key, specialKey, if (keyboardTheme == KeyboardTheme.DARK) .5f else .55f)
        fun cell(column: Int, row: Int, rowSpan: Int = 1, columnSpan: Int = 1): RectF {
            val left = edge + column * (keyWidth + gap)
            val topY = top + row * (keyHeight + gap)
            return RectF(left, topY, left + keyWidth * columnSpan + gap * (columnSpan - 1), topY + keyHeight * rowSpan + gap * (rowSpan - 1))
        }
        fun functionBox(box: RectF, radius: Float = dp(9)) {
            rounded(canvas, box, radius, if (isPressed(box)) pressedKey else functionKey)
        }
        fun letterKey(column: Int, row: Int, letters: String, code: String) {
            val box = cell(column, row)
            key(canvas, box)
            centeredLabel(canvas, letters, box, keyLetterSize(18f), white, Paint.Align.CENTER)
            target(box, keySound = true) {
                punctuationStripVisible = false
                actions.typePinyin(code)
            }
        }
        fun plainInputKey(column: Int, row: Int, text: String, value: String = text, columnSpan: Int = 1) {
            val box = cell(column, row, columnSpan = columnSpan)
            key(canvas, box)
            centeredLabel(canvas, text, box, 18f, white, Paint.Align.CENTER)
            target(box, keySound = true, previewLabel = value) {
                punctuationStripVisible = false
                actions.typeEnglish(value)
            }
        }

        if (nineKeyNumberPad) {
            val pinyin = cell(0, 0)
            functionBox(pinyin)
            centeredLabel(canvas, "拼音", pinyin, 18f, white, Paint.Align.CENTER)
            target(pinyin, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                nineKeyNumberPad = false
                punctuationStripVisible = false
                invalidate()
            }

            plainInputKey(1, 0, "1")
            plainInputKey(2, 0, "2")
            plainInputKey(3, 0, "3")

            val delete = cell(4, 0)
            functionBox(delete)
            lucide(canvas, R.drawable.ic_lucide_delete, delete.centerX(), delete.centerY(), dp(23), white)
            target(delete, repeat = true, keySound = true) { actions.backspace() }

            val symbolMore = cell(0, 1)
            functionBox(symbolMore)
            centeredLabel(canvas, "#@¥", symbolMore, 18f, white, Paint.Align.CENTER)
            target(symbolMore, hapticFeedback = null) {
                punctuationStripVisible = false
                symbolsPage = 1
                actions.toggleSymbols()
            }

            plainInputKey(1, 1, "4")
            plainInputKey(2, 1, "5")
            plainInputKey(3, 1, "6")

            val more = cell(4, 1)
            functionBox(more)
            centeredLabel(canvas, "更多", more, 18f, white, Paint.Align.CENTER)
            target(more, hapticFeedback = null) {
                punctuationStripVisible = false
                symbolsPage = 1
                actions.toggleSymbols()
            }

            val english = cell(0, 2)
            functionBox(english)
            centeredLabel(canvas, "ABC", english, 18f, white, Paint.Align.CENTER)
            target(english, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                nineKeyNumberPad = false
                punctuationStripVisible = false
                actions.selectMode(KeyboardMode.ENGLISH)
            }

            plainInputKey(1, 2, "7")
            plainInputKey(2, 2, "8")
            plainInputKey(3, 2, "9")

            val enter = cell(4, 2, rowSpan = 2)
            rounded(canvas, enter, dp(9), if (isPressed(enter)) pressedKey else accent)
            centeredLabel(canvas, "发送", enter, 18f, Color.WHITE, Paint.Align.CENTER)
            target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }

            val emoji = cell(0, 3)
            functionBox(emoji)
            lucide(canvas, R.drawable.ic_lucide_smile, emoji.centerX(), emoji.centerY(), dp(23), white)
            target(emoji, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                punctuationStripVisible = false
                emojiPanel = true; candidateExpanded = false; invalidate()
            }

            plainInputKey(1, 3, "，")
            plainInputKey(2, 3, "0")

        val space = cell(3, 3)
        key(canvas, space)
        centeredLabel(canvas, "空格", space, 18f, white, Paint.Align.CENTER)
        target(
            space,
            keySound = true,
            longPressAction = { actions.startDictation() },
            releaseAction = { actions.finishDictation() }
        ) { actions.pressTextSpace(true) }

            val bottomTop = top + keyHeight * 4 + gap * 4
            val touchBottom = bottomTop + bottomActionHeight
            val switchKeyboard = RectF(edge, bottomTop, edge + keyWidth, touchBottom)
            val bottomIconY = switchKeyboard.centerY()
            lucide(canvas, R.drawable.ic_lucide_globe, switchKeyboard.centerX(), bottomIconY, dp(24), muted)
            target(switchKeyboard, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.switchInputMethod() }

        val voice = RectF(width - edge - keyWidth, bottomTop, width - edge, touchBottom)
        drawDictationMic(canvas, voice.centerX(), bottomIconY)
        target(voice, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.toggleDictation() }
        return
    }

        val symbols = cell(0, 0)
        functionBox(symbols)
        centeredLabel(canvas, "123", symbols, 18f, white, Paint.Align.CENTER)
        target(symbols, hapticFeedback = null) {
            punctuationStripVisible = false
            nineKeyNumberPad = true
            invalidate()
        }

        val punctuation = cell(1, 0)
        key(canvas, punctuation)
        centeredLabel(canvas, "，。?!", punctuation, 18f, white, Paint.Align.CENTER)
        target(punctuation, keySound = true, previewLabel = "，") {
            punctuationStripVisible = true
            candidateExpanded = false
            emojiPanel = false
            actions.punctuationKeyTapped()
            invalidate()
        }

        letterKey(2, 0, "ABC", "2")
        letterKey(3, 0, "DEF", "3")

        val delete = cell(4, 0)
        functionBox(delete)
        lucide(canvas, R.drawable.ic_lucide_delete, delete.centerX(), delete.centerY(), dp(23), white)
        target(delete, repeat = true, keySound = true) { actions.backspace() }

        val symbolMore = cell(0, 1)
        functionBox(symbolMore)
        centeredLabel(canvas, "#@¥", symbolMore, 18f, white, Paint.Align.CENTER)
        target(symbolMore, hapticFeedback = null) {
            punctuationStripVisible = false
            symbolsPage = 1
            actions.toggleSymbols()
        }

        letterKey(1, 1, "GHI", "4")
        letterKey(2, 1, "JKL", "5")
        letterKey(3, 1, "MNO", "6")

        val kaomojiKey = cell(4, 1)
        key(canvas, kaomojiKey)
        centeredLabel(canvas, "^_^", kaomojiKey, 15f, white, Paint.Align.CENTER)
        target(kaomojiKey, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            punctuationStripVisible = false
            emojiPanel = false
            candidateExpanded = false
            kaomojiStripVisible = !kaomojiStripVisible
            invalidate()
        }

        val english = cell(0, 2)
        functionBox(english)
        centeredLabel(canvas, "ABC", english, 18f, white, Paint.Align.CENTER)
        target(english, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            punctuationStripVisible = false
            actions.selectMode(KeyboardMode.ENGLISH)
        }

        letterKey(1, 2, "PQRS", "7")
        letterKey(2, 2, "TUV", "8")
        letterKey(3, 2, "WXYZ", "9")

        val enter = cell(4, 2, rowSpan = 2)
        rounded(canvas, enter, dp(9), if (isPressed(enter)) pressedKey else accent)
        centeredLabel(canvas, "发送", enter, 18f, Color.WHITE, Paint.Align.CENTER)
        target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }

        val emoji = cell(0, 3)
        functionBox(emoji)
        lucide(canvas, R.drawable.ic_lucide_smile, emoji.centerX(), emoji.centerY(), dp(23), white)
        target(emoji, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            punctuationStripVisible = false
            emojiPanel = true; candidateExpanded = false; invalidate()
        }

        val pickPinyin = cell(1, 3)
        key(canvas, pickPinyin)
        centeredLabel(canvas, "选拼音", pickPinyin, 18f, white, Paint.Align.CENTER)
        target(pickPinyin, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            punctuationStripVisible = false
            if (showSpellingRow()) {
                spellingScroll = if (spellingScroll >= spellingMaxScroll) 0f
                    else (spellingScroll + width * .65f).coerceAtMost(spellingMaxScroll)
            } else candidateExpanded = true
            emojiPanel = false
            invalidate()
        }

        val space = cell(2, 3, columnSpan = 2)
        key(canvas, space)
        centeredLabel(canvas, "空格", space, 18f, white, Paint.Align.CENTER)
        target(
            space,
            keySound = true,
            longPressAction = { actions.startDictation() },
            releaseAction = { actions.finishDictation() }
        ) { actions.pressTextSpace(true) }

        val bottomTop = top + keyHeight * 4 + gap * 4
        val touchBottom = bottomTop + bottomActionHeight
        val switchKeyboard = RectF(edge, bottomTop, edge + keyWidth, touchBottom)
        val bottomIconY = switchKeyboard.centerY()
        lucide(canvas, R.drawable.ic_lucide_globe, switchKeyboard.centerX(), bottomIconY, dp(24), muted)
        target(switchKeyboard, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.switchInputMethod() }

        val voice = RectF(width - edge - keyWidth, bottomTop, width - edge, touchBottom)
        drawDictationMic(canvas, voice.centerX(), bottomIconY)
        target(voice, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.toggleDictation() }
    }

    /** 键盘右下角的麦克风：平时灰色，听写中粉色高亮并随音量脉动。 */
    private fun drawDictationMic(canvas: Canvas, cx: Float, cy: Float) {
        if (!dictationActive) {
            lucide(canvas, R.drawable.ic_lucide_mic, cx, cy, dp(24), muted)
            return
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(56, 255, 107, 157)
        canvas.drawCircle(cx, cy, dp(21) + meter * dp(6), paint)
        paint.color = Color.rgb(255, 107, 157)
        canvas.drawCircle(cx, cy, dp(16) + meter * dp(4), paint)
        paint.style = Paint.Style.FILL_AND_STROKE
        lucide(canvas, R.drawable.ic_lucide_mic, cx, cy, dp(22), Color.WHITE)
        postInvalidateOnAnimation()
    }

    private fun candidates(
        canvas: Canvas,
        candidateItems: List<PinyinCandidate>,
        emptyLabel: String,
        dismissible: Boolean = false,
        onPick: (PinyinCandidate) -> Unit
    ) {
        val spellingHeight = if (mode == KeyboardMode.PINYIN) spellingRowHeight() else 0f
        if (spellingHeight > 0) drawSpellingRow(canvas)
        val y = if (mode == KeyboardMode.VOICE) dp(291) else dp(11) + spellingHeight
        val textSize = (18 + candidateTextSizeLevel * 2).toFloat()
        val rowHeight = maxOf(dp(37), sp(textSize.toInt() + 16))
        val expandWidth = dp(43)
        val strip = RectF(dp(5), y, width - dp(5) - expandWidth, y + rowHeight)
        candidateStrip = RectF(0f, y - dp(4), width.toFloat(), y + rowHeight + dp(5))
        paint.textSize = sp(textSize.toInt())
        paint.typeface = Typeface.DEFAULT_BOLD
        if (candidateItems.isEmpty()) {
            label(canvas, emptyLabel, strip.left + dp(8), strip.centerY() + dp(7), 14f, muted, Paint.Align.LEFT)
            return
        }
        val widths = candidateItems.map { maxOf(dp(32), paint.measureText(it.text) + dp(18)) }
        val contentWidth = widths.sum() + dp(7) * (widths.size - 1).coerceAtLeast(0)
        candidateMaxScroll = (contentWidth - strip.width()).coerceAtLeast(0f)
        candidateScrollX = candidateScrollX.coerceIn(0f, candidateMaxScroll)
        canvas.save()
        canvas.clipRect(strip)
        var cursor = strip.left - candidateScrollX
        candidateItems.forEachIndexed { index, candidate ->
            val box = RectF(cursor, y, cursor + widths[index], y + rowHeight)
            cursor += widths[index] + dp(7)
            if (box.right < strip.left || box.left > strip.right) return@forEachIndexed
            if (index == 0) rounded(canvas, box, dp(7), key)
            val baseline = box.centerY() - (paint.ascent() + paint.descent()) / 2f
            label(canvas, candidate.text, box.centerX(), baseline, textSize, white, Paint.Align.CENTER, true)
            val visibleBox = RectF(box).apply { intersect(strip) }
            target(visibleBox, keySound = true) { onPick(candidate) }
        }
        canvas.restore()
        val expand = RectF(width - dp(5) - expandWidth, y, width - dp(5), y + rowHeight)
        rounded(canvas, expand, dp(8), key)
        // Next-word predictions (no live composition) get a dismiss button:
        // there is nothing to expand, and users need a way to clear the bar to
        // reach the mode tabs. Real composition keeps the expand chevron.
        if (dismissible) {
            lucide(canvas, R.drawable.ic_lucide_x, expand.centerX(), expand.centerY(), dp(18), muted)
            target(expand, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.dismissPredictions() }
        } else {
            lucide(canvas, if (candidateExpanded) R.drawable.ic_lucide_chevron_down else R.drawable.ic_lucide_chevron_right, expand.centerX(), expand.centerY(), dp(18), muted)
            target(expand, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                candidateExpanded = !candidateExpanded
                emojiPanel = false
                invalidate()
            }
        }
    }

    private fun drawPunctuationStrip(canvas: Canvas) {        val y = dp(11)
        val rowHeight = dp(42)
        val expandWidth = dp(43)
        val strip = RectF(dp(5), y, width - dp(5) - expandWidth, y + rowHeight)
        candidateStrip = RectF(0f, y - dp(4), width.toFloat(), y + rowHeight + dp(5))
        val symbols = nineKeySymbols.ifEmpty { listOf("，", "。", "？", "！", "…", "：", "、", "～") }
        val cellWidth = strip.width() / symbols.size
        canvas.save()
        canvas.clipRect(strip)
        symbols.forEachIndexed { index, symbol ->
            val box = RectF(strip.left + index * cellWidth, y, strip.left + (index + 1) * cellWidth, y + rowHeight)
            val size = if (symbol.length > 1) 18f else 23f
            centeredLabel(canvas, symbol, box, size, white, Paint.Align.CENTER)
            val visibleBox = RectF(box).apply { intersect(strip) }
            target(visibleBox, keySound = true, previewLabel = symbol) {
                punctuationStripVisible = false
                actions.choosePunctuation(symbol)
                invalidate()
            }
        }
        canvas.restore()
        val collapse = RectF(width - dp(5) - expandWidth, y, width - dp(5), y + rowHeight)
        rounded(canvas, collapse, dp(8), key)
        lucide(canvas, R.drawable.ic_lucide_chevron_down, collapse.centerX(), collapse.centerY(), dp(18), muted)
        target(collapse, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            punctuationStripVisible = false
            invalidate()
        }
    }

    /** iOS-style kaomoji bar: even slots keep the rhythm of the punctuation
     *  strip; width-based layout silently dropped single-glyph ♪ ♡ chips. */
    private fun drawKaomojiStrip(canvas: Canvas) {
        val y = dp(11)
        val rowHeight = dp(42)
        val expandWidth = dp(43)
        val strip = RectF(dp(5), y, width - dp(5) - expandWidth, y + rowHeight)
        candidateStrip = RectF(0f, y - dp(4), width.toFloat(), y + rowHeight + dp(5))
        canvas.save()
        canvas.clipRect(strip)
        paint.textSize = sp(15)
        paint.typeface = Typeface.DEFAULT
        val slot = strip.width() / kaomoji.size
        kaomoji.forEachIndexed { index, entry ->
            val box = RectF(strip.left + index * slot, y, strip.left + (index + 1) * slot, y + rowHeight)
            centeredLabel(canvas, entry, box, 15f, white, Paint.Align.CENTER)
            target(box, keySound = true) {
                kaomojiStripVisible = false
                actions.typeEnglish(entry)
                invalidate()
            }
        }
        canvas.restore()
        val collapse = RectF(width - dp(5) - expandWidth, y, width - dp(5), y + rowHeight)
        rounded(canvas, collapse, dp(8), key)
        lucide(canvas, R.drawable.ic_lucide_chevron_down, collapse.centerX(), collapse.centerY(), dp(18), muted)
        target(collapse, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            kaomojiStripVisible = false
            invalidate()
        }
    }

    private fun drawExpandedCandidates(canvas: Canvas) {
        val items = when {
            mode == KeyboardMode.ENGLISH -> englishCandidates
            else -> pinyinCandidates
        }
        val top = dp(58) + spellingRowHeight()
        val gap = dp(7)
        val columns = 4
        val cellWidth = (width - dp(10) - gap * (columns - 1)) / columns
        val cellHeight = dp(48)
        // Keep every expanded candidate above the system navigation bar. A later
        // paged candidate view can expose more items without creating dead rows.
        items.take(16).forEachIndexed { index, candidate ->
            val row = index / columns
            val column = index % columns
            val left = dp(5) + column * (cellWidth + gap)
            val box = RectF(left, top + row * (cellHeight + gap), left + cellWidth, top + row * (cellHeight + gap) + cellHeight)
            key(canvas, box)
            fittedLabel(canvas, candidate.text, box, 17f, white, true)
            target(box, keySound = true) {
                candidateExpanded = false
                if (mode == KeyboardMode.ENGLISH) actions.chooseEnglishCandidate(candidate.text) else actions.chooseCandidate(candidate)
            }
        }
        val close = RectF(width - dp(48), dp(8), width - dp(8), dp(48))
        rounded(canvas, close, dp(18), key)
        lucide(canvas, R.drawable.ic_lucide_chevron_down, close.centerX(), close.centerY(), dp(18), muted)
        target(close) { candidateExpanded = false; invalidate() }
    }

    /** Emoji panel categories. Kept to pre-Unicode-13 glyphs so every system font renders them. */
    private val emojiCategories: List<Pair<String, List<String>>> = listOf(
        "常用" to listOf("😀", "😂", "🥰", "😍", "😘", "😊", "🤣", "😉", "😭", "🥺", "😌", "😏", "🤔", "😅", "👍", "❤️", "💕", "🎉", "✨", "🌹", "🤩", "😜", "🤗", "😎", "🥳", "🙏", "💪", "💋", "🙈", "🎂", "🍻", "🌈", "🎁", "🔥", "💯", "😁", "😴", "🤝", "🌸", "☀️"),
        "笑脸" to listOf("😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪", "😝", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🥵", "🥶", "😵", "🤯", "🥳", "😎", "🤓", "😕", "🙁", "😮", "😲", "😳", "🥺", "😢", "😭", "😱", "😤", "😡"),
        "心情" to listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "💌", "💋", "🌹", "🥀", "💐", "🌷", "🌸", "💮", "🌺", "🌻", "🌼", "🍀", "🌿", "🍁", "🌙", "⭐", "🌟", "✨", "💫", "☀️", "🌈", "❄️", "🔥", "💤", "🎉", "🎊", "🎈", "🎁", "🎵", "🎶", "💎", "🏆"),
        "手势" to listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤝", "🙏", "💪", "✍️", "💅", "🤳", "👏", "🙌", "👐", "🤲", "👀", "👁️", "🧠", "🤠"),
        "动物" to listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐜", "🐢", "🐍", "🐙", "🦀"),
        "食物" to listOf("🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🥑", "🥔", "🥕", "🌽", "🥒", "🍞", "🧀", "🍗", "🍖", "🍤", "🍳", "🍟", "🍕", "🌭", "🍔", "🌮", "🍜", "🍣", "🍭", "🍩", "🍪", "🎂", "🍰", "🍫", "🍬", "☕", "🍵", "🥤", "🍻"),
        "符号" to listOf("✨", "⭐", "🌟", "💫", "⚡", "💥", "🔥", "🎉", "🎊", "🎈", "🎁", "🏆", "⚽", "🏀", "🎮", "🎵", "🎶", "💤", "💢", "💯", "❓", "❗", "✅", "❌", "⭕", "🚫", "♻️", "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚫", "⚪", "🔒", "🔑", "📅", "⏰", "💰", "🎯", "💎")
    )

    private fun drawEmojiKeyboard(canvas: Canvas) {
        val top = dp(62)
        val edge = dp(5)
        val gap = dp(7)
        val bottom = contentBottom() - keyboardBottomReserve()
        val tabHeight = dp(34)
        val barHeight = dp(46)
        // Category tabs across the top of the panel.
        val tabCount = emojiCategories.size
        val tabGap = dp(7)
        val tabWidth = (width - edge * 2 - tabGap * (tabCount - 1)) / tabCount
        emojiCategories.forEachIndexed { index, (name, _) ->
            val left = edge + index * (tabWidth + tabGap)
            val chip = RectF(left, top, left + tabWidth, top + tabHeight)
            val active = index == emojiCategory
            rounded(canvas, chip, chip.height() / 2f, if (active) accent else key)
            centeredLabel(canvas, name, chip, 12f, if (active) Color.WHITE else muted, Paint.Align.CENTER)
            target(chip, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                if (emojiCategory != index) {
                    emojiCategory = index
                    emojiPage = 0
                    invalidate()
                }
            }
        }
        // Paged emoji grid.
        val gridTop = top + tabHeight + gap
        val barTop = bottom - barHeight
        val rows = 4
        val columns = 7
        val gridGap = dp(7)
        val cellWidth = (width - edge * 2 - gridGap * (columns - 1)) / columns
        val cellHeight = (barTop - gap - gridTop - gridGap * (rows - 1)) / rows
        val emojis = emojiCategories[emojiCategory].second
        val perPage = rows * columns
        val pages = ((emojis.size + perPage - 1) / perPage).coerceAtLeast(1)
        emojiPage = emojiPage.coerceIn(0, pages - 1)
        emojis.drop(emojiPage * perPage).take(perPage).forEachIndexed { index, emoji ->
            val row = index / columns
            val column = index % columns
            val left = edge + column * (cellWidth + gridGap)
            val y = gridTop + row * (cellHeight + gridGap)
            val box = RectF(left, y, left + cellWidth, y + cellHeight)
            key(canvas, box)
            fittedLabel(canvas, emoji, box, 24f, white, false)
            target(box, keySound = true) { actions.typeEnglish(emoji) }
        }
        // Bottom bar: back to letters, page control, emoji delete.
        val backWidth = dp(76)
        val back = RectF(edge, barTop + gap, edge + backWidth, bottom)
        key(canvas, back)
        centeredLabel(canvas, "返回键盘", back, 13f, white, Paint.Align.CENTER)
        target(back, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { emojiPanel = false; invalidate() }
        val delete = RectF(width - edge - backWidth, barTop + gap, width - edge, bottom)
        key(canvas, delete)
        lucide(canvas, R.drawable.ic_lucide_delete, delete.centerX(), delete.centerY(), dp(22), white)
        target(delete, repeat = true, keySound = true) { actions.backspace() }
        if (pages > 1) {
            val midLeft = back.right + gap
            val midRight = delete.left - gap
            val midCenter = (midLeft + midRight) / 2f
            val prevBox = RectF(midLeft, barTop + gap, midCenter, bottom)
            val nextBox = RectF(midCenter, barTop + gap, midRight, bottom)
            if (emojiPage > 0) {
                lucide(canvas, R.drawable.ic_lucide_chevron_left, midLeft + dp(18), (barTop + gap + bottom) / 2f, dp(16), muted)
            }
            if (emojiPage < pages - 1) {
                lucide(canvas, R.drawable.ic_lucide_chevron_right, midRight - dp(18), (barTop + gap + bottom) / 2f, dp(16), muted)
            }
            val dotGap = dp(12)
            val dotsWidth = dotGap * (pages - 1)
            var dotX = midCenter - dotsWidth / 2f
            val dotY = (barTop + gap + bottom) / 2f
            paint.style = Paint.Style.FILL
            for (pageIndex in 0 until pages) {
                paint.color = if (pageIndex == emojiPage) accent else muted
                canvas.drawCircle(dotX, dotY, if (pageIndex == emojiPage) dp(4) else dp(3), paint)
                dotX += dotGap
            }
            target(prevBox, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                if (emojiPage > 0) { emojiPage--; invalidate() }
            }
            target(nextBox, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
                if (emojiPage < pages - 1) { emojiPage++; invalidate() }
            }
        }
    }

    private fun letters(canvas: Canvas, chars: String, y: Float, h: Float, pinyin: Boolean, inset: Float, keyWidth: Float) {
        val gap = dp(if (mode == KeyboardMode.ENGLISH) 7 else 4)
        chars.forEachIndexed { index, character ->
            val x = inset + index * (keyWidth + gap)
            val box = RectF(x, y, x + keyWidth, y + h)
            key(canvas, box)
            val output = if (pinyin || uppercase) upperLabels[character.code] else lowerLabels[character.code]
            centeredLabel(canvas, output, box, keyLetterSize(if (mode == KeyboardMode.ENGLISH) 18f else if (pinyin || uppercase) 23f else 25f), white, Paint.Align.CENTER)
            target(box, keySound = true, previewLabel = output) {
                if (pinyin) actions.typePinyin(character.toString()) else {
                    actions.typeEnglishLetter(output)
                    if (uppercase && !capsLock) {
                        uppercase = false
                        automaticUppercase = false
                        invalidate()
                    }
                }
            }
        }
    }

    private fun thirdRow(canvas: Canvas, y: Float, h: Float, pinyin: Boolean, keyWidth: Float) {
        val gap = dp(if (mode == KeyboardMode.ENGLISH) 7 else 4)
        // 删除键明显加宽（比 Shift 宽一档），既好按，视觉重量也更接近底部发送键。
        val shiftWidth = dp(44).toFloat()
        val maxDelete = width - dp(10) - keyWidth * 7 - gap * 8 - shiftWidth
        val deleteWidth = minOf(dp(56).toFloat(), maxDelete).coerceAtLeast(dp(44).toFloat())
        val chars = "zxcvbnm"
        val rowWidth = shiftWidth + deleteWidth + keyWidth * chars.length + gap * (chars.length + 1)
        val edge = (width - rowWidth) / 2f
        val shift = RectF(edge, y, edge + shiftWidth, y + h)
        // One-shot shift keeps the plain key look; caps lock stands out in accent.
        val shiftBg = when {
            capsLock -> accent
            uppercase -> key
            else -> if (isPressed(shift)) pressedKey else specialKey
        }
        rounded(canvas, shift, dp(10), shiftBg)
        if (pinyin) {
            centeredLabel(canvas, "'词", shift, 14f, white, Paint.Align.CENTER)
        } else {
            lucide(canvas, R.drawable.ic_lucide_arrow_big_up, shift.centerX(), shift.centerY(), dp(20), if (capsLock) Color.WHITE else white)
        }
        target(shift, keySound = true) {
            if (pinyin) actions.typePinyin("'") else {
                val now = SystemClock.elapsedRealtime()
                when {
                    capsLock -> { capsLock = false; uppercase = false }
                    uppercase -> if (now - lastShiftTapAt < 380L) capsLock = true else uppercase = false
                    else -> uppercase = true
                }
                automaticUppercase = false
                lastShiftTapAt = now
                invalidate()
            }
        }
        chars.forEachIndexed { index, character ->
            val x = edge + shiftWidth + gap + index * (keyWidth + gap)
            val box = RectF(x, y, x + keyWidth, y + h)
            key(canvas, box)
            val output = if (pinyin || uppercase) upperLabels[character.code] else lowerLabels[character.code]
            centeredLabel(canvas, output, box, keyLetterSize(if (mode == KeyboardMode.ENGLISH) 18f else if (pinyin || uppercase) 23f else 25f), white, Paint.Align.CENTER)
            target(box, keySound = true, previewLabel = output) {
                if (pinyin) actions.typePinyin(character.toString()) else {
                    actions.typeEnglishLetter(output)
                    if (uppercase && !capsLock) {
                        uppercase = false
                        automaticUppercase = false
                        invalidate()
                    }
                }
            }
        }
        val back = RectF(edge + rowWidth - deleteWidth, y, edge + rowWidth, y + h)
        rounded(canvas, back, dp(10), if (isPressed(back)) pressedKey else specialKey)
        lucide(canvas, R.drawable.ic_lucide_delete, back.centerX(), back.centerY(), dp(25), white)
        target(
            back,
            repeat = true,
            keySound = true
        ) { actions.backspace() }
    }

    private fun bottomRow(canvas: Canvas, y: Float, h: Float, pinyin: Boolean, keyWidth: Float) {
        val gap = dp(4)
        val edge = dp(5)
        val numberWidth = dp(48)
        val emojiWidth = dp(40)
        val punctuationWidth = if (punctuationShortcuts) dp(40) else 0f
        val languageWidth = dp(44)
        val enterWidth = keyWidth * 2 + gap
        val number = RectF(edge, y, edge + numberWidth, y + h)
        val emoji = RectF(number.right + gap, y, number.right + gap + emojiWidth, y + h)
        val punctuation = RectF(emoji.right + gap, y, emoji.right + gap + punctuationWidth, y + h)
        val languageLeft = if (punctuationShortcuts) punctuation.right + gap else emoji.right + gap
        val language = RectF(languageLeft, y, languageLeft + languageWidth, y + h)
        val enter = RectF(width - edge - enterWidth, y, width - edge, y + h)
        val space = RectF(language.right + gap, y, enter.left - gap, y + h)
        rounded(canvas, number, dp(10), if (isPressed(number)) pressedKey else specialKey)
        key(canvas, emoji)
        if (punctuationShortcuts) {
            key(canvas, punctuation)
            label(canvas, if (pinyin) "。" else ".", punctuation.centerX(), punctuation.centerY() - dp(6), 14f, muted, Paint.Align.CENTER)
            label(canvas, if (pinyin) "，" else ",", punctuation.centerX(), punctuation.centerY() + dp(10), 18f, white, Paint.Align.CENTER)
            target(
                punctuation,
                keySound = true,
                longPressAction = { actions.typeEnglish(if (pinyin) "。" else ".") },
                swipeUpAction = { actions.typeEnglish(if (pinyin) "。" else ".") }
            ) { actions.typeEnglish(if (pinyin) "，" else ",") }
        }
        key(canvas, language)
        key(canvas, space)
        rounded(canvas, enter, dp(10), if (isPressed(enter)) pressedKey else accent)
        centeredLabel(canvas, "123", number, 16f, white, Paint.Align.CENTER)
        lucide(canvas, R.drawable.ic_lucide_smile, emoji.centerX(), emoji.centerY(), dp(22), white)
        centeredLabel(canvas, "中/EN", language, 12f, white, Paint.Align.CENTER)
        centeredLabel(canvas, "AI 输入法", space, 14f, muted, Paint.Align.CENTER)
        centeredLabel(canvas, if (pinyin) "\u62fc" else "EN", RectF(space.right - dp(32), space.top, space.right - dp(6), space.bottom), 12f, muted, Paint.Align.CENTER)
        centeredLabel(canvas, enterLabel, enter, 14f, Color.WHITE, Paint.Align.CENTER)
        target(number, hapticFeedback = null) { actions.toggleSymbols() }
        target(emoji, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            emojiPanel = true; candidateExpanded = false; invalidate()
        }
        target(language, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { toggleTextLanguage() }
        target(space, keySound = true, longPressAction = { actions.startDictation() }, releaseAction = { actions.finishDictation() }) { actions.pressTextSpace(pinyin) }
        target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }
    }

    private fun englishBottomRow(canvas: Canvas, y: Float, h: Float, keyWidth: Float) {
        val gap = dp(7)
        val edge = dp(5)
        val numberWidth = keyWidth * 1.5f + gap / 2f
        val emojiWidth = numberWidth
        val enterWidth = keyWidth * 2 + gap
        val number = RectF(edge, y, edge + numberWidth, y + h)
        val emoji = RectF(number.right + gap, y, number.right + gap + emojiWidth, y + h)
        val enter = RectF(width - edge - enterWidth, y, width - edge, y + h)
        val space = RectF(emoji.right + gap, y, enter.left - gap, y + h)
        rounded(canvas, number, dp(10), if (isPressed(number)) pressedKey else specialKey)
        key(canvas, emoji)
        key(canvas, space)
        rounded(canvas, enter, dp(10), if (isPressed(enter)) pressedKey else accent)
        centeredLabel(canvas, "123", number, 18f, white, Paint.Align.CENTER)
        lucide(canvas, R.drawable.ic_lucide_smile, emoji.centerX(), emoji.centerY(), dp(22), white)
        centeredLabel(canvas, "空格", space, 18f, white, Paint.Align.CENTER)
        centeredLabel(canvas, enterLabel.lowercase(), enter, 18f, Color.WHITE, Paint.Align.CENTER)
        target(number, hapticFeedback = null) { actions.toggleSymbols() }
        target(emoji, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            emojiPanel = true; candidateExpanded = false; invalidate()
        }
        target(space, keySound = true, longPressAction = { actions.startDictation() }, releaseAction = { actions.finishDictation() }) { actions.pressTextSpace(false) }
        target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }
    }

    private fun drawQwertyBottomActions(canvas: Canvas, top: Float, edge: Float) {
        val actionTop = top
        val touchBottom = top + dp(58)
        val iconY = (actionTop + touchBottom) / 2f
        val actionWidth = (width - edge * 2 - dp(7) * 4) / 5f
        val switchKeyboard = RectF(edge, actionTop, edge + dp(116), touchBottom)
        lucide(canvas, R.drawable.ic_lucide_chevron_left, switchKeyboard.left + dp(16), iconY, dp(24), muted)
        label(canvas, "返回拼音", switchKeyboard.left + dp(34), iconY + dp(5), 14f, muted, Paint.Align.LEFT)
        target(switchKeyboard, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            actions.returnToNineKeyPinyin()
        }

        val voice = RectF(width - edge - actionWidth, actionTop, width - edge, touchBottom)
        drawDictationMic(canvas, voice.centerX(), iconY)
        target(voice, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) { actions.toggleDictation() }
    }

    private fun toggleTextLanguage() {
        if (mode == KeyboardMode.VOICE) {
            // Super Say owns an editor inside this view. Switching its keyboard
            // language must not replace the active top-level tab.
            superSayPinyin = !superSayPinyin
            candidateExpanded = false
            emojiPanel = false
            invalidate()
        } else {
            actions.selectMode(KeyboardMode.TEXT)
        }
    }

    private fun drawCursorSlider(canvas: Canvas) {
        val bottom = contentBottom()
        // Keep the cursor slider in the middle only. The bottom-left IME switcher
        // and bottom-right voice button live in the same bottom reserve; a full
        // width slider would intercept their touches before normal key targets.
        val sideReserved = ((width - dp(10)) / 5f + dp(18)).coerceAtLeast(dp(76))
        val track = RectF(sideReserved, bottom - dp(25), width - sideReserved, bottom - dp(7))
        cursorSlider = RectF(track).apply { inset(0f, -dp(10)) }
        rounded(canvas, track, track.height() / 2f, tabBackground)
        val thumbX = cursorSliderThumbX.takeIf { it.isFinite() }?.coerceIn(track.left, track.right) ?: track.centerX()
        rounded(canvas, RectF(thumbX - dp(20), track.top - dp(4), thumbX + dp(20), track.bottom + dp(4)), dp(12), key)
    }

    private fun drawSymbols(canvas: Canvas) {
        val top = dp(62)
        val gap = dp(7)
        val keyHeight = (contentBottom() - top - gap - keyboardBottomReserve().coerceAtLeast(dp(30)) - dp(58) - gap * 3) / 4f
        val edge = dp(5)
        val symbolWidth = (width - edge * 2 - dp(7) * 9) / 10f
        // Main keyboard symbol pages follow the common mobile "123" -> "#+=" flow.
        // Keep this independent from Ask/Super Say panels.
        val symbolPages = listOf(
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
                listOf(".", ",", "?", "!", "'", "[", "]", "{", "}", "…")
            ),
            listOf(
                listOf("#", "%", "+", "*", "=", "\\", "|", "~", "<", ">"),
                listOf("_", "^", "`", "·", "•", "√", "π", "÷", "×", "§"),
                listOf("€", "£", "¥", "¢", "©", "®", "™", "°", "℃", "№")
            ),
            listOf(
                listOf("【", "】", "《", "》", "「", "」", "『", "』", "〔", "〕"),
                listOf("￥", "——", "……", "·", "～", "•", "‖", "°", "℃", "§"),
                listOf("＾", "＿", "〃", "♪", "♡", "☆", "★", "→", "※", "〓")
            )
        )
        symbolsPage = symbolsPage.coerceIn(0, symbolPages.size - 1)
        symbolPages[symbolsPage].forEachIndexed { rowIndex, row ->
            symbolsRow(canvas, row, top + (keyHeight + gap) * rowIndex, keyHeight, symbolWidth)
        }
        val y = top + (keyHeight + gap) * 3
        val controlWidth = symbolWidth * 2 + dp(7)
        val pageKeyWidth = dp(44)
        val enter = RectF(width - edge - controlWidth, y, width - edge, y + keyHeight)
        val deleteWidth = dp(43)
        val delete = RectF(enter.left - dp(7) - deleteWidth, y, enter.left - dp(7), y + keyHeight)
        // 符号分组翻页器：一个宽键划三个点击区，‹ 上一组 / 页码 / › 下一组，
        // 首尾循环。页码告知共几组与当前位置，符号本身自解释，无需文字说明。
        val pager = RectF(edge, y, edge + controlWidth + dp(7) + pageKeyWidth, y + keyHeight)
        val space = RectF(pager.right + dp(7), y, delete.left - dp(7), y + keyHeight)
        key(canvas, pager)
        key(canvas, space)
        key(canvas, delete)
        rounded(canvas, enter, dp(10), if (isPressed(enter)) pressedKey else accent)
        centeredLabel(canvas, "${symbolsPage + 1}/${symbolPages.size}", pager, 16f, white, Paint.Align.CENTER)
        lucide(canvas, R.drawable.ic_lucide_chevron_left, pager.left + dp(24), pager.centerY(), dp(20), white)
        lucide(canvas, R.drawable.ic_lucide_chevron_right, pager.right - dp(24), pager.centerY(), dp(20), white)
        centeredLabel(canvas, "空格", space, 18f, white, Paint.Align.CENTER)
        lucide(canvas, R.drawable.ic_lucide_delete, delete.centerX(), delete.centerY(), dp(23), white)
        lucide(canvas, R.drawable.ic_lucide_move_right, enter.centerX(), enter.centerY(), dp(21), Color.WHITE)
        val pagerZone = pager.width() / 3f
        val pagerPrev = RectF(pager.left, pager.top, pager.left + pagerZone, pager.bottom)
        val pagerNext = RectF(pager.right - pagerZone, pager.top, pager.right, pager.bottom)
        target(pagerPrev, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            symbolsPage = (symbolsPage + symbolPages.size - 1) % symbolPages.size
            invalidate()
        }
        target(pagerNext, hapticFeedback = HapticFeedbackConstants.CONTEXT_CLICK) {
            symbolsPage = (symbolsPage + 1) % symbolPages.size
            invalidate()
        }
        target(space, keySound = true) { actions.typeEnglish(" ") }
        target(delete, repeat = true, keySound = true) { actions.backspace() }
        target(enter, keySound = true, longPressAction = { actions.newline() }) { actions.enter() }
        drawQwertyBottomActions(canvas, top + keyHeight * 4 + gap * 4, edge)
    }

    private fun symbolsRow(
        canvas: Canvas,
        symbols: List<String>,
        y: Float,
        h: Float,
        keyWidth: Float
    ) {
        val gap = dp(7)
        val rowWidth = keyWidth * symbols.size + gap * (symbols.size - 1)
        val edge = (width - rowWidth) / 2f
        symbols.forEachIndexed { index, symbol ->
            val x = edge + index * (keyWidth + gap)
            val box = RectF(x, y, x + keyWidth, y + h)
            key(canvas, box)
            centeredLabel(canvas, symbol, box, 18f, white, Paint.Align.CENTER)
            target(box, keySound = true, previewLabel = symbol) { actions.typeEnglish(symbol) }
        }
    }

    private fun key(canvas: Canvas, box: RectF) = rounded(canvas, box, dp(9), if (isPressed(box)) pressedKey else key)

    private fun gradientRounded(canvas: Canvas, box: RectF, radius: Float, startColor: Int, endColor: Int) {
        val previous = paint.shader
        paint.shader = LinearGradient(box.left, box.top, box.right, box.bottom, startColor, endColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.shader = previous
    }

    private fun fittedLabel(
        canvas: Canvas,
        value: String,
        box: RectF,
        size: Float,
        color: Int,
        bold: Boolean
    ) {
        var renderedSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        while (renderedSize > 11f) {
            paint.textSize = sp(renderedSize.toInt())
            if (paint.measureText(value) <= box.width() - dp(8)) break
            renderedSize -= 1f
        }
        canvas.save()
        canvas.clipRect(box)
        label(canvas, value, box.centerX(), box.centerY() + sp(renderedSize.toInt()) * .35f, renderedSize, color, Paint.Align.CENTER, bold)
        canvas.restore()
    }

    private fun uniformCandidateLabel(canvas: Canvas, value: String, box: RectF, size: Float, color: Int) {
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = dp(size.toInt())
        val maxWidth = box.width() - dp(10)
        var rendered = value
        while (rendered.length > 1 && paint.measureText("$rendered...") > maxWidth) rendered = rendered.dropLast(1)
        if (rendered != value) rendered += "..."
        canvas.save()
        canvas.clipRect(box)
        label(canvas, rendered, box.centerX(), box.centerY() + dp(size.toInt()) * .35f, size, color, Paint.Align.CENTER, true)
        canvas.restore()
    }

    private fun leftClippedLabel(canvas: Canvas, value: String, box: RectF, size: Float, color: Int) {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sp(size.toInt())
        val maxWidth = box.width() - dp(24)
        var rendered = value
        while (rendered.length > 1 && paint.measureText("$rendered...") > maxWidth) rendered = rendered.dropLast(1)
        if (rendered != value) rendered += "..."
        canvas.save()
        canvas.clipRect(box)
        label(canvas, rendered, box.left + dp(12), box.centerY() + dp(size.toInt()) * .35f, size, color, Paint.Align.LEFT)
        canvas.restore()
    }

    private fun progress(canvas: Canvas, box: RectF, fraction: Float) {
        val clip = Path().apply { addRoundRect(box, box.height() / 2, box.height() / 2, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = white
        canvas.drawRect(box.left, box.top, box.left + box.width() * fraction, box.bottom, paint)
        canvas.restore()
    }

    private fun streamProgress(canvas: Canvas, box: RectF) {
        val elapsed = (System.currentTimeMillis() - polishingStreamStartedAt).coerceAtLeast(0L)
        progress(canvas, box, (elapsed % 1_500L) / 1_500f)
        postInvalidateOnAnimation()
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }

    private fun lucide(
        canvas: Canvas,
        @DrawableRes icon: Int,
        centerX: Float,
        centerY: Float,
        size: Float,
        tint: Int,
        alpha: Float = 1f
    ) {
        val drawable = iconCache[icon] ?: AppCompatResources.getDrawable(context, icon)?.mutate()?.also {
            iconCache.put(icon, it)
        } ?: return
        drawable.setTint(tint)
        drawable.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val half = size / 2f
        drawable.setBounds((centerX - half).toInt(), (centerY - half).toInt(), (centerX + half).toInt(), (centerY + half).toInt())
        drawable.draw(canvas)
    }

    private fun rounded(canvas: Canvas, box: RectF, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        if (isPressed(box)) {
            canvas.save()
            canvas.scale(.95f, .95f, box.centerX(), box.centerY())
            canvas.drawRoundRect(box, radius, radius, paint)
            canvas.restore()
        } else canvas.drawRoundRect(box, radius, radius, paint)
    }

    private fun circle(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align,
        bold: Boolean = false,
        alpha: Float = 1f
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        paint.textSize = sp(size.toInt())
        paint.textAlign = align
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
        paint.alpha = 255
    }

    private fun centeredLabel(canvas: Canvas, value: String, box: RectF, size: Float, color: Int, align: Paint.Align) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sp(size.toInt())
        paint.textAlign = align
        paint.typeface = Typeface.DEFAULT
        val baseline = box.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(value, box.centerX(), baseline, paint)
    }

    private fun wave(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = maxOf(dp(4), size / 10f)
        floatArrayOf(.42f, .72f, 1f, .72f, .42f).forEachIndexed { index, scale ->
            val px = x + (index - 2) * size / 4.2f
            val half = size * scale / 2f
            canvas.drawLine(px, y - half, px, y + half, paint)
        }
    }

    private fun microphone(canvas: Canvas, x: Float, y: Float, color: Int, size: Float, alpha: Float = 1f) {
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        rounded(canvas, RectF(x - size * .18f, y - size * .42f, x + size * .18f, y + size * .13f), size * .2f, color)
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = size * .12f
        canvas.drawArc(RectF(x - size * .34f, y - size * .15f, x + size * .34f, y + size * .40f), 0f, 180f, false, paint)
        canvas.drawLine(x, y + size * .40f, x, y + size * .58f, paint)
        canvas.drawLine(x - size * .20f, y + size * .58f, x + size * .20f, y + size * .58f, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun loadingDots(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        val step = (System.currentTimeMillis() % 800L).toFloat() / 800f
        repeat(6) { index ->
            val phase = (step + index / 6f) % 1f
            val dotAlpha = (.3f + .7f * kotlin.math.abs(phase * 2f - 1f)) * alpha
            paint.alpha = (dotAlpha * 255).toInt()
            circle(canvas, x + (index - 2.5f) * dp(12), y, dp(3), actionIcon)
        }
        paint.alpha = 255
        postInvalidateOnAnimation()
    }

    private fun voiceBars(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        val factors = floatArrayOf(.48f, .72f, 1f, .86f, .64f, .42f)
        paint.color = actionIcon
        paint.alpha = (alpha * 255).toInt()
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(4)
        factors.forEachIndexed { index, factor ->
            val motion = .72f + .28f * kotlin.math.sin(System.currentTimeMillis() / 68.0 + index * 1.1).toFloat()
            val height = dp(6) + dp(36) * (meter.coerceAtMost(1f) * factor * motion)
            val px = x + (index - 2.5f) * dp(8)
            canvas.drawLine(px, y - height / 2, px, y + height / 2, paint)
        }
        paint.alpha = 255
        postInvalidateOnAnimation()
    }

    private fun backArrow(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeWidth = dp(2)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x + size, y, x - size, y, paint)
        canvas.drawLine(x - size, y, x - size * .35f, y - size * .55f, paint)
        canvas.drawLine(x - size, y, x - size * .35f, y + size * .55f, paint)
    }

    private fun pencil(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        canvas.save()
        canvas.rotate(-40f, x, y)
        canvas.drawRect(x - size * .18f, y - size, x + size * .18f, y + size * .7f, paint)
        canvas.drawLine(x - size * .18f, y - size, x, y - size * 1.35f, paint)
        canvas.drawLine(x + size * .18f, y - size, x, y - size * 1.35f, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    private fun closeGlyph(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeWidth = dp(2)
        canvas.drawLine(x - size, y - size, x + size, y + size, paint)
        canvas.drawLine(x + size, y - size, x - size, y + size, paint)
    }

    private fun backspace(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = dp(2)
        val shape = Path().apply {
            moveTo(x - size, y)
            lineTo(x - size * .4f, y - size * .6f)
            lineTo(x + size, y - size * .6f)
            lineTo(x + size, y + size * .6f)
            lineTo(x - size * .4f, y + size * .6f)
            close()
        }
        canvas.drawPath(shape, paint)
        canvas.drawLine(x, y - size * .27f, x + size * .45f, y + size * .27f, paint)
        canvas.drawLine(x + size * .45f, y - size * .27f, x, y + size * .27f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun shiftGlyph(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = dp(2)
        val shape = Path().apply {
            moveTo(x, y - size)
            lineTo(x - size, y)
            lineTo(x - size * .45f, y)
            lineTo(x - size * .45f, y + size)
            lineTo(x + size * .45f, y + size)
            lineTo(x + size * .45f, y)
            lineTo(x + size, y)
            close()
        }
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
    }

    private fun enterGlyph(canvas: Canvas, x: Float, y: Float, color: Int, size: Float) {
        paint.color = color
        paint.strokeWidth = dp(2)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x + size * .45f, y - size * .55f, x + size * .45f, y, paint)
        canvas.drawLine(x + size * .45f, y, x - size * .55f, y, paint)
        canvas.drawLine(x - size * .55f, y, x - size * .18f, y - size * .35f, paint)
        canvas.drawLine(x - size * .55f, y, x - size * .18f, y + size * .35f, paint)
    }

    private fun target(
        box: RectF,
        enabled: Boolean = true,
        repeat: Boolean = false,
        hold: Boolean = false,
        keySound: Boolean = false,
        hapticFeedback: Int? = HapticFeedbackConstants.KEYBOARD_TAP,
        longPressAction: (() -> Unit)? = null,
        swipeUpAction: (() -> Unit)? = null,
        releaseAction: (() -> Unit)? = null,
        previewLabel: String? = null,
        action: () -> Unit
    ) {
        targets += TouchTarget(box, enabled, repeat, hold, keySound, hapticFeedback, longPressAction, swipeUpAction, releaseAction, previewLabel, action)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            spellingTouch = showSpellingRow() && event.y >= spellingRowTop() && event.y < spellingRowTop() + spellingRowHeight()
            if (spellingTouch) {
                spellingDownX = event.x
                spellingStartScroll = spellingScroll
                return true
            }
        } else if (spellingTouch) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    spellingScroll = (spellingStartScroll + spellingDownX - event.x).coerceIn(0f, spellingMaxScroll)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.x - spellingDownX) < dp(8)) {
                        targets.lastOrNull { it.enabled && it.box.contains(event.x, event.y) }?.action?.invoke()
                    }
                    spellingTouch = false
                }
                MotionEvent.ACTION_CANCEL -> spellingTouch = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                // Candidate selection always wins when the header has been
                // replaced by the candidate strip.
                candidateDragging = candidateStrip?.contains(event.x, event.y) == true
                polishRelationDragging = !candidateDragging && polishRelationViewport?.contains(event.x, event.y) == true
                polishRelationDragStartScroll = polishRelationScrollX
                cursorSliderDragging = false
                if (polishRelationDragging) {
                    pressedBox = null
                    activeTarget = targets.lastOrNull { it.enabled && it.box.contains(event.x, event.y) }
                }
                if (!candidateDragging && modeTabs?.contains(event.x, event.y) == true) {
                    modeTabDragging = true
                    modeTabLastIndex = -1
                    selectModeAt(event.x, emitFeedback = false)
                    return true
                }
                clipboardTracking = mode == KeyboardMode.CLIPBOARD && clipboardViewport?.contains(event.x, event.y) == true
                if (clipboardTracking) {
                    clipboardDragStartScroll = clipboardScrollY
                    clipboardTouchedEntry = clipboardRows.lastOrNull { it.box.contains(event.x, event.y) }?.entry
                    clipboardInitialReveal = clipboardTouchedEntry?.id == clipboardRevealId
                    clipboardSwipeOffset = if (clipboardInitialReveal) dp(68) else 0f
                    pressedBox = null
                    activeTarget = null
                    invalidate()
                    return true
                }
                if (candidateDragging) {
                    candidateScroller.forceFinished(true)
                    candidateVelocityTracker?.recycle()
                    candidateVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                answerDragging = !candidateDragging && answerViewport?.contains(event.x, event.y) == true
                candidateDragStartScroll = candidateScrollX
                answerDragStartScroll = answerScrollY
                val hit = targets.lastOrNull { it.enabled && it.box.contains(event.x, event.y) }
                cursorSliderDragging = hit == null && !candidateDragging && cursorSlider?.contains(event.x, event.y) == true
                if (cursorSliderDragging) {
                    cursorSliderLastX = event.x
                    cursorSliderThumbX = event.x
                    pressedBox = null
                    activeTarget = null
                    invalidate()
                    return true
                }
                activeTarget = hit
                primaryPointerId = event.getPointerId(0)
                if (hit != null) {
                    secondaryTouches[primaryPointerId] = hit
                    pressedPointers[primaryPointerId] = hit.box
                    dispatchPressFeedback(primaryPointerId, hit)
                }
                activeTargetCancelled = false
                longPressTriggered = false
                swipeUpTriggered = false
                pressedBox = hit?.box
                if (hit?.repeat == true) {
                    repeatAction = hit.action
                    repeatHaptic = hit.hapticFeedback
                    repeatIntervalMs = 92L
                    postDelayed(repeatBackspace, 360L)
                }
                if (hit?.longPressAction != null) postDelayed(longPressRunnable, 420L)
                if (hit?.hold == true) hit.action()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val hit = targets.lastOrNull { it.enabled && it.box.contains(event.getX(pointerIndex), event.getY(pointerIndex)) }
                if (hit != null) {
                    secondaryTouches[pointerId] = hit
                    pressedPointers[pointerId] = hit.box
                    dispatchPressFeedback(pointerId, hit)
                    invalidate()
                }
                cancelledSecondaryTouches.remove(pointerId)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (modeTabDragging) {
                    selectModeAt(event.x, emitFeedback = true)
                    return true
                }
                if (cursorSliderDragging) {
                    val step = dp(12)
                    val delta = event.x - cursorSliderLastX
                    val moves = (kotlin.math.abs(delta) / step).toInt()
                    if (moves > 0) {
                        actions.moveCursorBy(if (delta > 0f) moves else -moves)
                        cursorSliderLastX += if (delta > 0f) moves * step else -moves * step
                        emitHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    cursorSliderThumbX = event.x
                    invalidate()
                    return true
                }
                secondaryTouches.toMap().forEach { (pointerId, target) ->
                    val pointerIndex = event.findPointerIndex(pointerId)
                    if (pointerIndex < 0 || !target.box.contains(event.getX(pointerIndex), event.getY(pointerIndex))) {
                        cancelledSecondaryTouches += pointerId
                        pressedPointers.remove(pointerId)
                    }
                }
                if (clipboardTracking) {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        clipboardScrollY = (clipboardDragStartScroll - dy).coerceIn(0f, clipboardMaxScroll)
                        clipboardSwipeOffset = 0f
                    } else {
                        clipboardSwipeOffset = if (clipboardInitialReveal) {
                            (dp(68) - dx).coerceIn(0f, dp(68))
                        } else {
                            (-dx).coerceIn(0f, dp(68))
                        }
                    }
                    invalidate()
                } else if (polishRelationDragging) {
                    val dx = event.x - touchDownX
                    polishRelationScrollX = (polishRelationDragStartScroll - dx).coerceIn(0f, polishRelationMaxScroll)
                    if (kotlin.math.abs(dx) > dp(4)) {
                        activeTargetCancelled = true
                        pressedBox = null
                    }
                    invalidate()
                } else if (candidateDragging) {
                    candidateVelocityTracker?.addMovement(event)
                    candidateScrollX = (candidateDragStartScroll - (event.x - touchDownX)).coerceIn(0f, candidateMaxScroll)
                    pressedBox = null
                    invalidate()
                } else if (answerDragging) {
                    answerScrollY = (answerDragStartScroll - (event.y - touchDownY)).coerceIn(0f, answerMaxScroll)
                    pressedBox = null
                    invalidate()
                } else if (longPressTriggered && longPressVoiceBox != null) {
                    val closeRight = if (showCloseButton) width - dp(54) else width - dp(8)
                    val close = RectF(closeRight - dp(38), dp(10), closeRight, dp(48))
                    if (close.contains(event.x, event.y)) {
                        removeCallbacks(longPressRunnable)
                        repeatAction = null
                        repeatHaptic = null
                        actions.cancelVoice()
                        activeTargetCancelled = true
                        longPressTriggered = false
                        longPressVoiceBox = null
                        animateHoldOverlay(show = false)
                        invalidate()
                        return true
                    }
                    // Select translation by the actual floating target instead of a
                    // distance from the initial touch, so it tracks the visible capsule.
                    val capsuleHeight = dp(68)
                    val capsuleBottom = height - height * .52f * holdOverlayProgress - dp(18)
                    val capsuleWidth = width * .66f
                    val translationTarget = RectF(
                        width / 2f - capsuleWidth / 2f,
                        capsuleBottom - capsuleHeight - dp(18),
                        width / 2f + capsuleWidth / 2f,
                        capsuleBottom + dp(18)
                    )
                    val selected = translationTarget.contains(event.x, event.y)
                    if (selected != longPressTranslationSelected) {
                        longPressTranslationSelected = selected
                        actions.setLongPressTranslation(selected)
                        emitHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    pressedBox = activeTarget?.box
                    invalidate()
                } else {
                    val active = activeTarget
                    if (!swipeUpTriggered && active?.swipeUpAction != null && event.y <= touchDownY - dp(22) &&
                        kotlin.math.abs(event.x - touchDownX) <= dp(36)) {
                        removeCallbacks(longPressRunnable)
                        active.swipeUpAction.invoke()
                        swipeUpTriggered = true
                        activeTargetCancelled = true
                        pressedBox = null
                        emitHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
                        invalidate()
                        return true
                    }
                    if (active != null && !hitsWithSlop(active.box, event.x, event.y)) {
                        activeTargetCancelled = true
                        pressedBox = null
                        pressedPointers.remove(primaryPointerId)
                        removeCallbacks(repeatBackspace)
                        removeCallbacks(longPressRunnable)
                        repeatAction = null
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val target = secondaryTouches.remove(pointerId)
                val cancelled = cancelledSecondaryTouches.remove(pointerId)
                if (target != null && !cancelled && hitsWithSlop(target.box, event.getX(pointerIndex), event.getY(pointerIndex)) &&
                    (!target.repeat || actions.canBackspace())) {
                    if (pointerId == primaryPointerId && longPressTriggered) {
                        target.releaseAction?.invoke()
                    } else {
                        target.action.invoke()
                    }
                }
                pressedPointers.remove(pointerId)
                feedbackPointers.remove(pointerId)
                if (pointerId == primaryPointerId) {
                    removeCallbacks(repeatBackspace)
                    removeCallbacks(longPressRunnable)
                    repeatAction = null
                    repeatHaptic = null
                    activeTarget = null
                    activeTargetCancelled = false
                    primaryPointerId = MotionEvent.INVALID_POINTER_ID
                    if (longPressTriggered) {
                        longPressTriggered = false
                        longPressVoiceBox = null
                        animateHoldOverlay(show = false)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (modeTabDragging) {
                    selectModeAt(event.x, emitFeedback = modeTabLastIndex < 0)
                    modeTabDragging = false
                    modeTabLastIndex = -1
                    performClick()
                    return true
                }
                removeCallbacks(repeatBackspace)
                removeCallbacks(longPressRunnable)
                repeatAction = null
                repeatHaptic = null
                if (cursorSliderDragging) {
                    cursorSliderDragging = false
                    cursorSliderThumbX = Float.NaN
                    invalidate()
                    performClick()
                    return true
                }
                if (clipboardTracking) {
                    val entry = clipboardTouchedEntry
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val revealAmount = clipboardSwipeOffset
                    clipboardTracking = false
                    clipboardTouchedEntry = null
                    clipboardSwipeOffset = 0f
                    clipboardInitialReveal = false
                    when {
                        entry == null -> Unit
                        kotlin.math.abs(dy) > kotlin.math.abs(dx) -> Unit
                        kotlin.math.abs(dx) >= dp(8) -> {
                            clipboardRevealId = if (revealAmount >= dp(34)) entry.id else null
                        }
                        clipboardRevealId == entry.id && event.x >= width - dp(78) -> {
                            clipboardRevealId = null
                            actions.deleteClipboard(entry)
                        }
                        kotlin.math.abs(dx) < dp(8) -> {
                            clipboardRevealId = null
                            emitHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            actions.pasteClipboard(entry)
                        }
                    }
                    invalidate()
                    performClick()
                    return true
                }
                if (polishRelationDragging) {
                    val moved = kotlin.math.abs(event.x - touchDownX) > dp(4)
                    polishRelationDragging = false
                    if (moved) {
                        activeTarget = null
                        activeTargetCancelled = false
                        pressedBox = null
                        performClick()
                        return true
                    }
                }
                val wasDragging = candidateDragging && kotlin.math.abs(event.x - touchDownX) > dp(4)
                val wasAnswerDragging = answerDragging && kotlin.math.abs(event.y - touchDownY) > dp(4)
                candidateDragging = false
                polishRelationDragging = false
                answerDragging = false
                if (wasDragging || wasAnswerDragging) {
                    candidateVelocityTracker?.addMovement(event)
                    candidateVelocityTracker?.computeCurrentVelocity(1_000)
                    val velocity = (-(candidateVelocityTracker?.xVelocity ?: 0f)).toInt()
                    candidateVelocityTracker?.recycle()
                    candidateVelocityTracker = null
                    if (wasDragging && velocity != 0) {
                        candidateScroller.fling(
                            candidateScrollX.toInt(), 0, velocity, 0,
                            0, candidateMaxScroll.toInt(), 0, 0
                        )
                        postInvalidateOnAnimation()
                    }
                    pressedBox = null
                    performClick()
                    return true
                }
                candidateVelocityTracker?.recycle()
                candidateVelocityTracker = null
                val pointerId = event.getPointerId(event.actionIndex)
                val active = secondaryTouches.remove(pointerId) ?: activeTarget
                val cancelled = cancelledSecondaryTouches.remove(pointerId) || activeTargetCancelled
                val releasedOnOriginalTarget = !cancelled && active?.let { hitsWithSlop(it.box, event.x, event.y) } == true
                postDelayed({ pressedBox = null; invalidate() }, 32L)
                if (longPressTriggered) {
                    active?.releaseAction?.invoke()
                } else if (releasedOnOriginalTarget && (!active!!.repeat || actions.canBackspace())) {
                    active?.action?.invoke()
                }
                activeTarget = null
                activeTargetCancelled = false
                primaryPointerId = MotionEvent.INVALID_POINTER_ID
                longPressVoiceBox = null
                animateHoldOverlay(show = false)
                secondaryTouches.clear()
                cancelledSecondaryTouches.clear()
                pressedPointers.clear()
                feedbackPointers.clear()
                longPressTriggered = false
                swipeUpTriggered = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                modeTabDragging = false
                modeTabLastIndex = -1
                removeCallbacks(repeatBackspace)
                removeCallbacks(longPressRunnable)
                repeatAction = null
                repeatHaptic = null
                cursorSliderDragging = false
                cursorSliderThumbX = Float.NaN
                activeTarget?.takeIf { longPressTriggered }?.releaseAction?.invoke()
                activeTarget = null
                activeTargetCancelled = false
                primaryPointerId = MotionEvent.INVALID_POINTER_ID
                longPressVoiceBox = null
                animateHoldOverlay(show = false)
                secondaryTouches.clear()
                cancelledSecondaryTouches.clear()
                pressedPointers.clear()
                feedbackPointers.clear()
                longPressTriggered = false
                swipeUpTriggered = false
                candidateDragging = false
                candidateVelocityTracker?.recycle()
                candidateVelocityTracker = null
                answerDragging = false
                clipboardTracking = false
                clipboardTouchedEntry = null
                clipboardSwipeOffset = 0f
                clipboardInitialReveal = false
                pressedBox = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Pending key-repeat / long-press callbacks must never fire once the
        // keyboard surface is gone.
        removeCallbacks(repeatBackspace)
        removeCallbacks(longPressRunnable)
        repeatAction = null
        repeatHaptic = null
        activeTarget = null
        pressedBox = null
        candidateVelocityTracker?.recycle()
        candidateVelocityTracker = null
    }

    override fun computeScroll() {
        if (candidateScroller.computeScrollOffset()) {
            candidateScrollX = candidateScroller.currX.toFloat().coerceIn(0f, candidateMaxScroll)
            postInvalidateOnAnimation()
        }
    }

    /** Xiaomi routes Android's vibrator service through its system haptic engine. */
    private fun emitHaptic(type: Int) {
        if (hapticStrength == HapticStrength.OFF) return
        val now = SystemClock.elapsedRealtime()
        if (type == HapticFeedbackConstants.KEYBOARD_TAP && now - lastKeyboardHapticAtMs < KEYBOARD_HAPTIC_INTERVAL_MS) return
        if (type == HapticFeedbackConstants.KEYBOARD_TAP) lastKeyboardHapticAtMs = now
        if (hapticStrength == HapticStrength.SYSTEM) {
            performHapticFeedback(type)
            return
        }
        val baseDuration = when (type) {
            HapticFeedbackConstants.KEYBOARD_TAP -> 5L
            HapticFeedbackConstants.CONTEXT_CLICK -> 8L
            HapticFeedbackConstants.GESTURE_START -> 14L
            HapticFeedbackConstants.GESTURE_END -> 11L
            HapticFeedbackConstants.CONFIRM -> 18L
            HapticFeedbackConstants.REJECT -> 26L
            else -> 8L
        }
        val duration = baseDuration + when (hapticStrength) {
            HapticStrength.WEAK -> 0L
            HapticStrength.MEDIUM -> 3L
            HapticStrength.FAIRLY_STRONG -> 7L
            HapticStrength.STRONG -> 11L
            else -> 0L
        }
        val deviceVibrator = vibrator
        if (deviceVibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (hapticStrength == HapticStrength.STRONG && type != HapticFeedbackConstants.KEYBOARD_TAP) {
                    VibrationEffect.createWaveform(
                        longArrayOf(0L, duration, 16L, 5L),
                        intArrayOf(0, hapticStrength.amplitude, 0, 150),
                        -1
                    )
                } else VibrationEffect.createOneShot(duration, hapticStrength.amplitude)
                deviceVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                deviceVibrator.vibrate(duration)
            }
        } else {
            performHapticFeedback(type)
        }
    }

    private fun dispatchPressFeedback(pointerId: Int, target: TouchTarget) {
        if (!feedbackPointers.add(pointerId)) return
        target.hapticFeedback?.let(::emitHaptic)
        if (target.keySound) keySound.play(keyboardSoundVolume)
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
    private val fontScale: Float get() = resources.configuration.fontScale.coerceIn(1f, 1.3f)
    private fun sp(value: Int): Float = dp(value) * fontScale

    private fun selectModeAt(x: Float, emitFeedback: Boolean) {
        val tabs = modeTabs ?: return
        val index = ((x - tabs.left) / (tabs.width() / availableModes.size)).toInt()
            .coerceIn(0, availableModes.lastIndex)
        if (index == modeTabLastIndex) return
        modeTabLastIndex = index
        val target = availableModes[index]
        if (sensitive && target in listOf(KeyboardMode.VOICE, KeyboardMode.ASK)) return
        if (emitFeedback) emitHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
        actions.selectMode(target)
    }

    private fun decodeLogo(path: String): android.graphics.Bitmap? {
        if (path.isBlank()) return null
        return runCatching {
            BitmapFactory.Options().apply { inSampleSize = 2 }.let { options ->
                BitmapFactory.decodeFile(path, options)
            }
        }.getOrNull()
    }
    private fun keyLetterSize(size: Float): Float = if (isLandscape()) size * .8f else size
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun standardKeyboardTop(): Float = (if (!pinyinReady) dp(70) else dp(62)) + (if (mode == KeyboardMode.PINYIN) keyboardInputExtraHeight() else 0f)
    private fun showSpellingRow(): Boolean = (mode == KeyboardMode.PINYIN || (mode == KeyboardMode.VOICE && superSayPinyin)) && pinyinNineKey && pinyinBuffer.isNotBlank() && spellingOptions.isNotEmpty()
    private fun spellingRowHeight(): Float = if (showSpellingRow()) dp(40) else 0f

    private fun keyboardInputExtraHeight(): Float = if (!showSpellingRow()) 0f else if (mode == KeyboardMode.VOICE) dp(98) else dp(40)

    private fun spellingRowTop(): Float = if (mode == KeyboardMode.VOICE) dp(240) else 0f

    private fun drawSpellingRow(canvas: Canvas) {
        val top = spellingRowTop()
        val options = listOf("") + spellingOptions
        paint.textSize = sp(15)
        paint.typeface = Typeface.DEFAULT
        val widths = options.map { maxOf(dp(46), paint.measureText(it.ifBlank { "全部" }) + dp(24)) }
        spellingMaxScroll = (widths.sum() + dp(8) - width).coerceAtLeast(0f)
        spellingScroll = spellingScroll.coerceIn(0f, spellingMaxScroll)
        var left = dp(4) - spellingScroll
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), top + dp(40))
        options.forEachIndexed { index, spelling ->
            val box = RectF(left, top + dp(4), left + widths[index], top + dp(37))
            left = box.right
            if (box.right > 0 && box.left < width) {
                if (spelling == selectedSpelling) rounded(canvas, box, dp(10), tabBackground)
                label(canvas, spelling.ifBlank { "全部" }, box.centerX(), box.centerY() + dp(5), 15f,
                    if (spelling == selectedSpelling) accent else muted, Paint.Align.CENTER, spelling == selectedSpelling)
                val hit = RectF(box).apply { intersect(0f, top, width.toFloat(), top + dp(40)) }
                target(hit) { actions.choosePinyinSpelling(spelling) }
            }
        }
        canvas.restore()
    }
    private fun superSayKeyboardTop(): Float = dp(240) + (if (mode == KeyboardMode.VOICE) keyboardInputExtraHeight() else 0f)

    private fun contentBottom(): Float = height.toFloat() - dp(keyboardBottomOffsetLevel * 8)
    private fun keyboardBottomReserve(): Float = if (cursorSliderEnabled) dp(32) else dp(8)
    private fun hasComposition(): Boolean =
        ((mode == KeyboardMode.PINYIN || mode == KeyboardMode.VOICE) && pinyinBuffer.isNotBlank()) ||
            (mode == KeyboardMode.ENGLISH && englishBuffer.isNotBlank())
    private fun hasCompositionCandidates(): Boolean =
        if (mode == KeyboardMode.PINYIN || mode == KeyboardMode.VOICE) pinyinCandidates.isNotEmpty()
        else englishCandidates.isNotEmpty()

    private fun hasPredictions(): Boolean = mode in listOf(KeyboardMode.PINYIN, KeyboardMode.ENGLISH, KeyboardMode.SYMBOLS) &&
        predictionCandidates.isNotEmpty()
    private fun polishIndexAt(x: Float, textLeft: Float): Int {
        paint.textSize = sp(13)
        paint.typeface = Typeface.DEFAULT
        val relative = (x - textLeft).coerceAtLeast(0f)
        var index = 0
        while (index < polishDraft.length) {
            val next = polishDraft.offsetByCodePoints(index, 1)
            val midpoint = (paint.measureText(polishDraft.substring(0, index)) +
                paint.measureText(polishDraft.substring(0, next))) / 2f
            if (relative < midpoint) return index
            index = next
        }
        return polishDraft.length
    }
    private fun topModeFor(mode: KeyboardMode, options: List<KeyboardMode>): KeyboardMode =
        if (mode in listOf(KeyboardMode.PINYIN, KeyboardMode.ENGLISH) && KeyboardMode.TEXT in options) KeyboardMode.TEXT else mode
    private fun isActive(state: VoiceUiState) = state == VoiceUiState.Listening
    /** Hit test with a small slop so fast, slightly-sliding taps still register. */
    private fun hitsWithSlop(box: RectF, x: Float, y: Float): Boolean {
        val slop = dp(6)
        return x >= box.left - slop && x <= box.right + slop &&
            y >= box.top - slop && y <= box.bottom + slop
    }
    private fun isPressed(box: RectF): Boolean =
        sameBox(pressedBox, box) || pressedPointers.values.any { sameBox(it, box) }
    private fun sameBox(first: RectF?, second: RectF?): Boolean = first != null && second != null &&
        kotlin.math.abs(first.left - second.left) < 1f && kotlin.math.abs(first.top - second.top) < 1f &&
        kotlin.math.abs(first.right - second.right) < 1f && kotlin.math.abs(first.bottom - second.bottom) < 1f
    private data class TouchTarget(
        val box: RectF,
        val enabled: Boolean,
        val repeat: Boolean,
        val hold: Boolean,
        val keySound: Boolean,
        val hapticFeedback: Int?,
        val longPressAction: (() -> Unit)?,
        val swipeUpAction: (() -> Unit)?,
        val releaseAction: (() -> Unit)?,
        val previewLabel: String?,
        val action: () -> Unit
    )

    private data class AnswerLine(
        val text: String,
        val sizeSp: Float,
        val color: Int,
        val bold: Boolean,
        val alpha: Float,
        val height: Float
    )

    private data class ClipboardRow(val entry: ClipboardEntry, val box: RectF)

    private companion object {
        const val KEYBOARD_HAPTIC_INTERVAL_MS = 16L
    }

}
