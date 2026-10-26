package com.weike.ime.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Half-screen AI chat opened from the keyboard toolbar.
 *
 * The top of the screen stays translucent so the host app is still visible; the
 * panel sits at the bottom and is pushed up by the IME through window insets.
 * Every answer is previewed first; tapping "插入" hands the text back to the IME,
 * which commits it into the field the user was typing in. Images chosen from the
 * gallery are sent as part of the question, not run through a local OCR pass.
 */
class AiChatActivity : AppCompatActivity() {

    private val settings by lazy { AiChatSettings(this) }
    private val historyStore by lazy { ChatHistoryStore(this) }
    private val client = AiChatClient()
    private val messages = mutableListOf<ChatMessage>()
    private val attachments = mutableListOf<ChatImage>()
    private var conversationId: String = java.util.UUID.randomUUID().toString()
    private lateinit var panel: LinearLayout

    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var statusLabel: TextView
    private lateinit var attachmentStrip: HorizontalScrollView
    private lateinit var attachmentRow: LinearLayout
    private lateinit var sendButton: TextView

    private var sending = false

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AiChatSettings.DEFAULT_MAX_IMAGES)
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val remaining = settings.config().maxImages - attachments.size
        if (remaining <= 0) {
            toast("最多只能附加 ${settings.config().maxImages} 张图片")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val encoded = withContext(Dispatchers.IO) {
                uris.take(remaining).mapNotNull { encodeImage(it) }
            }
            attachments += encoded
            renderAttachments()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        showConfigurationHintIfNeeded()
        if (savedInstanceState == null && !settings.config().isComplete()) {
            // First run: the chat is useless without an endpoint, so lead with
            // the configuration dialog instead of an empty page.
            inputField.post { if (!isFinishing) showSettingsDialog() }
        }
        // Debug-only adb hook used to check IME insets without a physical tap.
        if (intent?.getBooleanExtra(EXTRA_AUTOFOCUS, false) == true) {
            inputField.post {
                inputField.requestFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // ---------------------------------------------------------------- UI ----

    private fun buildUi() {
        val root = FrameLayout(this)

        // Tap the dimmed area above the panel to dismiss it, like a bottom sheet.
        val scrim = View(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = topRoundedPanel()
        }

        panel.addView(buildHeader())

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(
                LinearLayout(this@AiChatActivity).also { messagesContainer = it },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            messagesContainer.apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(14))
            }
        }
        panel.addView(scrollView)

        statusLabel = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(C_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        panel.addView(statusLabel)

        attachmentStrip = HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            addView(
                LinearLayout(this@AiChatActivity).also { attachmentRow = it }.apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(14), dp(4), dp(14), dp(4))
                }
            )
        }
        panel.addView(attachmentStrip)

        panel.addView(buildComposer())

        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            initialPanelHeight()
        ).apply { gravity = Gravity.BOTTOM })

        // Android 15+ draws edge-to-edge regardless of adjustResize, so the
        // composer must be lifted by the IME insets explicitly.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottom = maxOf(ime, navigation)
            view.setPadding(0, 0, 0, bottom)
            // Keep the panel below the status bar when the IME pushes it up.
            val available = resources.displayMetrics.heightPixels - bottom - statusBar
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                height = minOf(initialPanelHeight(), available)
            }
            insets
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(root)
        root.requestApplyInsets()
        renderMessages()
    }

    private fun initialPanelHeight(): Int =
        (resources.displayMetrics.heightPixels * 0.62f).toInt()

    private fun topRoundedPanel(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.WHITE)
        val radius = dp(22).toFloat()
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(10), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "AI 助手"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
        })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(actionLabel("设置") { showSettingsDialog() })
        header.addView(actionLabel("历史") {
            startActivity(android.content.Intent(this, com.weike.ime.ChatHistoryActivity::class.java))
        })
        header.addView(actionLabel("新对话") { startNewConversation() })
        header.addView(actionLabel("关闭") { finish() })
        return header
    }

    private fun buildComposer(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(12))
            setBackgroundColor(C_PANEL)
        }

        bar.addView(TextView(this).apply {
            text = "＋"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(C_ACCENT)
            background = rounded(Color.TRANSPARENT, 22)
            isClickable = true
            setOnClickListener { openImagePicker() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(6)
            }
        })

        inputField = EditText(this).apply {
            hint = "输入问题，或只发图片…"
            textSize = 15f
            setTextColor(C_TEXT)
            setHintTextColor(C_MUTED)
            maxLines = 5
            minLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // Ask our IME for the 26-key layout and avoid the fullscreen
            // extract editor, which would cover the half-screen panel.
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            privateImeOptions = "weike_full_keyboard"
            background = rounded(Color.rgb(0xF4, 0xF1, 0xF2), 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(inputField)

        sendButton = TextView(this).apply {
            text = "↑"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(C_ACCENT, 22)
            isClickable = true
            setOnClickListener { send() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = dp(8)
            }
        }
        bar.addView(sendButton)
        return bar
    }

    private fun actionLabel(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(C_MUTED)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener { action() }
        }

    // ----------------------------------------------------------- messages ----

    private fun renderMessages() {
        messagesContainer.removeAllViews()
        if (messages.isEmpty()) {
            messagesContainer.addView(TextView(this).apply {
                text = "输入问题，或点左下角 ＋ 选图片。\n回答会先显示在这里，点「插入」写回输入框。"
                textSize = 14f
                setTextColor(C_MUTED)
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
            return
        }
        messages.forEach { messagesContainer.addView(messageView(it)) }
        messagesContainer.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun messageView(message: ChatMessage): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            gravity = if (message.role == "user") Gravity.END else Gravity.START
        }

        if (message.hasImages) {
            row.addView(TextView(this).apply {
                text = "已附 ${message.images.size} 张图片"
                textSize = 12f
                setTextColor(C_MUTED)
                setPadding(0, 0, 0, dp(4))
            })
        }

        val bubble = TextView(this).apply {
            text = message.text
            textSize = 15f
            setTextColor(if (message.failed) C_ERROR else C_TEXT)
            setTextIsSelectable(!message.failed)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(
                when {
                    message.failed -> Color.rgb(0xFD, 0xEE, 0xEE)
                    message.role == "user" -> C_USER_BUBBLE
                    else -> C_PANEL
                },
                16
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(bubble)

        if (message.role == "assistant" && !message.failed && message.text.isNotBlank()) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(dp(4), dp(2), 0, 0)
            }
            actions.addView(actionLabel("插入") { insert(message.text) })
            actions.addView(actionLabel("复制") { copy(message.text) })
            actions.addView(actionLabel("重发") { resend(message.text) })
            row.addView(actions)
        }
        return row
    }

    // ------------------------------------------------------------- images ----

    private fun openImagePicker() {
        runCatching {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            toast("无法打开图片选择器：${it.message}")
        }
    }

    private fun renderAttachments() {
        attachmentRow.removeAllViews()
        attachmentStrip.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachments.forEachIndexed { index, image ->
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    rightMargin = dp(8)
                }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.rgb(0xEE, 0xEA, 0xEB), 10)
                clipToOutline = true
            }
            cell.addView(thumb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            cell.addView(TextView(this).apply {
                text = "×"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(Color.argb(160, 0, 0, 0), 9)
                isClickable = true
                setOnClickListener {
                    if (index in attachments.indices) {
                        attachments.removeAt(index)
                        renderAttachments()
                    }
                }
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END)
            })
            attachmentRow.addView(cell)
            image.previewUri?.let { uri ->
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) { loadThumbnail(Uri.parse(uri)) }
                    if (bitmap != null) thumb.setImageBitmap(bitmap)
                }
            }
        }
    }

    // -------------------------------------------------------------- send ----

    private fun send() {
        if (sending) return
        val config = settings.config()
        if (!config.isComplete()) {
            showSettingsDialog()
            return
        }
        val typed = inputField.text?.toString()?.trim().orEmpty()
        val text = if (typed.isBlank() && attachments.isNotEmpty()) {
            config.defaultImagePrompt
        } else {
            typed
        }
        if (text.isBlank() && attachments.isEmpty()) return

        messages += ChatMessage("user", text, attachments.toList())
        inputField.setText("")
        attachments.clear()
        renderAttachments()
        renderMessages()
        persistHistory()

        sending = true
        sendButton.isEnabled = false
        sendButton.alpha = 0.5f
        statusLabel.text = "正在思考…"
        statusLabel.visibility = View.VISIBLE

        lifecycleScope.launch {
            client.complete(config, messages)
                .onSuccess { messages += ChatMessage("assistant", it) }
                .onFailure {
                    messages += ChatMessage(
                        "assistant",
                        it.message ?: "请求失败，请检查网络或模型配置",
                        failed = true
                    )
                }
            sending = false
            sendButton.isEnabled = true
            sendButton.alpha = 1f
            statusLabel.visibility = View.GONE
            renderMessages()
            persistHistory()
        }
    }

    private fun resend(question: String) {
        if (sending) return
        val config = settings.config()
        if (!config.isComplete()) {
            showSettingsDialog()
            return
        }
        messages += ChatMessage("user", question)
        renderMessages()
        persistHistory()
        sending = true
        sendButton.isEnabled = false
        statusLabel.text = "正在思考…"
        statusLabel.visibility = View.VISIBLE
        lifecycleScope.launch {
            client.complete(config, messages)
                .onSuccess { messages += ChatMessage("assistant", it) }
                .onFailure {
                    messages += ChatMessage("assistant", it.message ?: "请求失败", failed = true)
                }
            sending = false
            sendButton.isEnabled = true
            statusLabel.visibility = View.GONE
            renderMessages()
            persistHistory()
        }
    }

    /** Saves the current conversation; called on each exchange and when leaving. */
    private fun persistHistory() {
        if (messages.isEmpty()) return
        val snapshot = messages.map {
            HistoryMessage(
                role = it.role,
                text = it.text,
                imageCount = it.images.size,
                failed = it.failed
            )
        }
        val title = messages.firstOrNull { it.role == "user" }
            ?.text
            ?.trim()
            ?.take(28)
            .orEmpty()
            .ifBlank { "未命名对话" }
        val conversation = HistoryConversation(
            id = conversationId,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = snapshot
        )
        val store = historyStore
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.upsert(conversation) }
        }
    }

    private fun startNewConversation() {
        persistHistory()
        messages.clear()
        attachments.clear()
        conversationId = java.util.UUID.randomUUID().toString()
        renderAttachments()
        renderMessages()
    }

    override fun onStop() {
        persistHistory()
        super.onStop()
    }

    private fun insert(text: String) {
        if (text.isBlank()) return
        AiInsertBridge.offer(text)
        sendBroadcast(
            android.content.Intent(AiInsertBridge.ACTION_AI_INSERT).setPackage(packageName)
        )
        finish()
    }

    private fun copy(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("AI 回答", text))
        toast("已复制")
    }

    // ---------------------------------------------------------- settings ----

    private fun showConfigurationHintIfNeeded() {
        val config = settings.config()
        if (config.isComplete()) return
        statusLabel.text = "还没配置模型：点右上角「设置」填写接口地址、Key 和模型名"
        statusLabel.visibility = View.VISIBLE
    }

    private fun showSettingsDialog() {
        AiSettingsDialog.show(this) { saved ->
            if (saved.isComplete()) {
                statusLabel.visibility = View.GONE
                toast("已保存")
            } else {
                statusLabel.text = "配置不完整：地址、Key、模型名都要填"
                statusLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String,
        password: Boolean = false,
        multiline: Boolean = false
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(C_MUTED)
            setPadding(0, dp(10), 0, dp(4))
        })
        val edit = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(C_TEXT)
            setHintTextColor(Color.rgb(0xBB, 0xB0, 0xB4))
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            maxLines = if (multiline) 5 else 1
            background = rounded(Color.rgb(0xF4, 0xF1, 0xF2), 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        parent.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return edit
    }

    // -------------------------------------------------------------- util ----

    private fun encodeImage(uri: Uri): ChatImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1408) sample *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } ?: return null
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
        bitmap.recycle()
        ChatImage(
            base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            previewUri = uri.toString()
        )
    }.getOrNull()

    private fun loadThumbnail(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 200) sample *= 2
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }.getOrNull()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_AUTOFOCUS = "autofocus"
        private val C_BG = 0xFFF9FA.toInt()
        private val C_PANEL = 0xFFFFFF
        private val C_TEXT = 0xFF2D3436.toInt()
        private val C_MUTED = 0xFF8D7E84.toInt()
        private val C_ACCENT = 0xFFFF6B9D.toInt()
        private val C_USER_BUBBLE = 0xFFFFE8EE.toInt()
        private val C_ERROR = 0xFFB3261E.toInt()
    }
}
