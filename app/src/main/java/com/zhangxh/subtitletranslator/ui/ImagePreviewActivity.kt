package com.zhangxh.subtitletranslator.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.zhangxh.subtitletranslator.R

/**
 * 单张调试截图的大图查看
 *
 * 图片加载由 [ImagePreviewViewModel] 负责；分享（FileProvider + chooser）属于界面交互，留在这里。
 */
class ImagePreviewActivity : BaseActivity() {

    companion object {
        private const val TAG = "ImagePreviewActivity"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_LABEL = "extra_label"
    }

    private val viewModel: ImagePreviewViewModel by viewModels()

    private lateinit var sessionId: String
    private lateinit var fileName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()

        val imageView = findViewById<ImageView>(R.id.imageView)
        findViewById<TextView>(R.id.tvLabel)?.text = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare)?.setOnClickListener { shareImage() }

        viewModel.uiState.observe(this) { state ->
            state.bitmap?.let { imageView.setImageBitmap(it) }
        }
        viewModel.messages.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.load(sessionId, fileName, resources.displayMetrics.widthPixels)
    }

    private fun shareImage() {
        val file = viewModel.sessionFile(sessionId, fileName)
        if (!file.exists()) {
            Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (fileName.endsWith(".png")) "image/png" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享截图"))
        } catch (e: Exception) {
            Log.e(TAG, "分享截图失败", e)
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
