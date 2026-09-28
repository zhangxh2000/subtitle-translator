package com.zhangxh.subtitletranslator.ui

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * 界面基类
 *
 * 统一给内容根布局留出状态栏/导航栏空间（见 [applySystemBarPadding]）。
 * 放在基类里而不是每个 Activity 各写一遍，是为了避免以后新增界面时漏掉，
 * 又出现标题栏被状态栏盖住的问题。
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onContentChanged() {
        super.onContentChanged()
        // setContentView 完成后才会走到这里，此时内容视图已就绪
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount > 0) {
            content.getChildAt(0).applySystemBarPadding()
        }
    }
}
