package com.zhangxh.subtitletranslator.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.ui.common.labelRes
import com.zhangxh.subtitletranslator.ui.common.toUiText
import com.zhangxh.subtitletranslator.util.CaptureRecord
import com.zhangxh.subtitletranslator.util.DebugCaptureStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 缩略图解码宽度上限，够清晰又不会占太多内存 */
private const val THUMBNAIL_WIDTH_PX = 400

/**
 * 识别截图查看界面
 *
 * 按时间倒序列出最近几次识别记录，每条展开「原始截图 / 裁剪区域 / 预处理图」三个阶段。
 * 数据与图片加载由 [DebugCaptureViewModel] 负责，这里只做渲染与导航。
 */
class DebugCaptureActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val viewModel: DebugCaptureViewModel by viewModels()
    private val adapter = CaptureAdapter(
        loadThumbnail = { recordId, fileName, onLoaded ->
            viewModel.loadThumbnail(recordId, fileName, THUMBNAIL_WIDTH_PX, onLoaded)
        },
        onOpenPreview = { record, fileName, label -> openPreview(record, fileName, label) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_capture)

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.tvEmpty)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear)?.setOnClickListener { viewModel.clear() }

        viewModel.uiState.observe(this) { state ->
            adapter.setData(state.items)
            emptyView.visibility = if (state.isEmpty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (state.isEmpty) View.GONE else View.VISIBLE
        }
        viewModel.messages.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message.resolve(this), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 打开单张图片的大图查看 */
    private fun openPreview(record: CaptureRecord, fileName: String, @StringRes labelRes: Int) {
        startActivity(
            Intent(this, ImagePreviewActivity::class.java).apply {
                putExtra(ImagePreviewActivity.EXTRA_SESSION_ID, record.id)
                putExtra(ImagePreviewActivity.EXTRA_FILE_NAME, fileName)
                putExtra(ImagePreviewActivity.EXTRA_LABEL_RES, labelRes)
            }
        )
    }

    private class CaptureAdapter(
        private val loadThumbnail: (String, String, (android.graphics.Bitmap?) -> Unit) -> Unit,
        private val onOpenPreview: (CaptureRecord, String, Int) -> Unit
    ) : RecyclerView.Adapter<CaptureAdapter.ViewHolder>() {

        private var items: List<CaptureRecord> = emptyList()

        fun setData(data: List<CaptureRecord>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_debug_capture, parent, false)
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val tvError: TextView = itemView.findViewById(R.id.tvError)
            private val tvCropInfo: TextView = itemView.findViewById(R.id.tvCropInfo)
            private val tvOcrText: TextView = itemView.findViewById(R.id.tvOcrText)
            private val imgScreenshot: ImageView = itemView.findViewById(R.id.imgScreenshot)
            private val imgSubtitle: ImageView = itemView.findViewById(R.id.imgSubtitle)
            private val imgProcessed: ImageView = itemView.findViewById(R.id.imgProcessed)

            fun bind(record: CaptureRecord) {
                val context = itemView.context
                tvTime.text = formatHeader(record)

                val error = record.error
                if (error != null) {
                    tvError.visibility = View.VISIBLE
                    tvError.text = context.getString(
                        R.string.debug_error_prefix,
                        error.toUiText().resolve(context)
                    )
                } else {
                    tvError.visibility = View.GONE
                }

                tvCropInfo.text = formatCropInfo(record)
                tvOcrText.text = if (record.ocrRawText.isBlank()) {
                    context.getString(R.string.debug_ocr_text_empty)
                } else {
                    context.getString(R.string.debug_ocr_text_prefix, record.ocrRawText)
                }

                bindImage(
                    imgScreenshot,
                    record,
                    DebugCaptureStore.FILE_SCREENSHOT,
                    R.string.debug_caption_screenshot
                )
                bindImage(
                    imgSubtitle,
                    record,
                    DebugCaptureStore.FILE_SUBTITLE,
                    R.string.debug_caption_crop
                )
                bindImage(
                    imgProcessed,
                    record,
                    DebugCaptureStore.FILE_PROCESSED,
                    R.string.debug_caption_processed_full
                )
            }

            private fun bindImage(
                imageView: ImageView,
                record: CaptureRecord,
                fileName: String,
                @StringRes labelRes: Int
            ) {
                // 用「会话 + 文件名」作为标识，避免列表复用后旧图覆盖新图
                val tag = "${record.id}/$fileName"
                imageView.tag = tag
                imageView.setImageDrawable(null)

                if (fileName !in record.imageFiles) {
                    imageView.setOnClickListener(null)
                    return
                }

                imageView.setOnClickListener { onOpenPreview(record, fileName, labelRes) }
                loadThumbnail(record.id, fileName) { bitmap ->
                    if (imageView.tag == tag) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }

            private fun formatHeader(record: CaptureRecord): String {
                val context = itemView.context
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(record.timestamp))
                val crop = record.cropInfo ?: return time
                return context.getString(
                    R.string.debug_header,
                    time,
                    context.getString(crop.orientation.labelRes()),
                    crop.screenWidth,
                    crop.screenHeight
                )
            }

            /** 把裁剪参数摊开写，方便判断字幕有没有落在裁剪框里 */
            private fun formatCropInfo(record: CaptureRecord): String {
                val context = itemView.context
                val crop = record.cropInfo
                val mode = context.getString(
                    OcrPreprocessMode.fromName(record.preprocessMode).labelRes()
                )
                val preprocess = context.getString(
                    R.string.debug_preprocess_info,
                    record.scaleFactor,
                    mode
                )
                if (crop == null) {
                    return context.getString(R.string.debug_crop_missing, preprocess)
                }

                val estimate = if (crop.videoHeight > 0) {
                    context.getString(R.string.debug_video_height_estimate, crop.videoHeight)
                } else {
                    ""
                }
                return context.getString(
                    R.string.debug_crop_range,
                    crop.top,
                    crop.bottom,
                    crop.height,
                    estimate,
                    preprocess
                )
            }
        }
    }
}
