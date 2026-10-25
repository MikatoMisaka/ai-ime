package com.weike.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.weike.ime.ime.WeikeInputMethodService
import kotlin.concurrent.thread

/**
 * 输入法无法直接弹相册选图，经由这个页面中转：
 * 直接读 MediaStore 展示相册九宫格（不依赖系统选图器——
 * 部分 ROM 的 DocumentsUI 选图会点不动/无法返回），选完把
 * URI 通过应用内广播交还给 IME 服务，随后立即退出。
 */
class ImagePickerActivity : AppCompatActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadGallery()
        } else {
            Toast.makeText(this, "需要照片权限才能选择聊天截图", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadGallery()
        } else {
            requestPermission.launch(permission)
        }
    }

    /** 放弃选图同样退回聊天界面，而不是落在同任务的主页面上。 */
    override fun onBackPressed() {
        moveTaskToBack(true)
        finish()
    }

    private fun loadGallery() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 244, 248))
        }
        root.addView(TextView(this).apply {
            text = "选择聊天截图"
            textSize = 18f
            setTextColor(Color.rgb(233, 30, 99))
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "截取TA发来的消息，AI帮你分析并生成回复"
            textSize = 12f
            setTextColor(Color.rgb(158, 130, 140))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        val gridHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(
            gridHolder,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        thread(name = "gallery-load") {
            val photos = mutableListOf<Uri>()
            runCatching {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        photos.add(
                            Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idColumn).toString()
                            )
                        )
                    }
                }
            }
            runOnUiThread {
                if (photos.isEmpty()) {
                    gridHolder.addView(TextView(this).apply {
                        text = "相册里还没有图片"
                        textSize = 14f
                        setTextColor(Color.rgb(158, 130, 140))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(60), 0, 0)
                    })
                    return@runOnUiThread
                }
                val columns = 3
                val pad = dp(4)
                val cell = (resources.displayMetrics.widthPixels - pad * (columns + 1) * 2 / 3) / columns
                photos.chunked(columns).forEach { rowPhotos ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(pad, pad / 2, pad, pad / 2)
                    }
                    rowPhotos.forEach { uri ->
                        val frame = FrameLayout(this)
                        row.addView(
                            frame,
                            LinearLayout.LayoutParams(cell, cell).apply { setMargins(pad / 2, 0, pad / 2, 0) }
                        )
                        val image = ImageView(this).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(Color.rgb(232, 224, 230))
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        frame.addView(image)
                        frame.setOnClickListener(
                            View.OnClickListener {
                                WeikeInputMethodService.pendingImageUri = uri
                                // 本页与主页面同任务：直接 finish 会把栈顶留成主页面，
                                // 用户看到的是"跳回了恋爱键盘"。退回后台让微信/短信
                                // 回到前台，键盘随后在聊天界面上方弹出。
                                moveTaskToBack(true)
                                finish()
                                // 广播是异步投递的，直接发会和任务切换引发的输入会话
                                // 重启赛跑，识别状态会被 onStartInput 的模式重置冲掉；
                                // 等聊天应用的输入会话稳定后再通知 IME 识别。
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    sendBroadcast(
                                        Intent(WeikeInputMethodService.ACTION_IMAGE_PICKED)
                                            .setPackage(packageName)
                                    )
                                }, 500)
                            }
                        )
                        thread(name = "thumb-load") {
                            val thumb = runCatching {
                                contentResolver.loadThumbnail(uri, Size(288, 288), null)
                            }.getOrNull()
                            runOnUiThread { if (!isFinishing) image.setImageBitmap(thumb) }
                        }
                    }
                    gridHolder.addView(
                        row,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    )
                }
            }
        }
    }
}
