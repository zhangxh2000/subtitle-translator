package com.zhangxh.subtitletranslator.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.util.DebugCaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 单张调试截图的大图查看
 *
 * 缩略图尺寸看不出笔画有没有被二值化吃掉、字幕有没有切在框外，所以需要按屏幕宽度
 * 重新解码一张清晰的图。截图存在应用私有目录，需要导出时通过 [FileProvider] 分享。
 */
class ImagePreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ImagePreviewActivity"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_LABEL = "extra_label"
    }

    private val store by lazy { DebugCaptureStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var sessionId: String
    private lateinit var fileName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()

        findViewById<TextView>(R.id.tvLabel)?.text = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare)?.setOnClickListener { shareImage() }

        loadImage()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** 按屏幕宽度解码，保证 1:1 左右显示，能看清笔画细节 */
    private fun loadImage() {
        val imageView = findViewById<ImageView>(R.id.imageView)
        val maxWidth = resources.displayMetrics.widthPixels

        scope.launch {
            val bitmap = store.loadImage(sessionId, fileName, maxWidth)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this@ImagePreviewActivity, "图片读取失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareImage() {
        val file = store.sessionFile(sessionId, fileName)
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
