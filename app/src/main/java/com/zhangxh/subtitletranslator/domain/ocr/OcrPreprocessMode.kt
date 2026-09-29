package com.zhangxh.subtitletranslator.domain.ocr

/**
 * OCR 预处理方式
 *
 * 背景：字幕颜色与视频背景接近时，全局固定阈值会把文字和背景判成同一种颜色，
 * 文字直接消失。这不是调阈值能解决的 —— 文字亮度 150、背景亮度 140 时，
 * 阈值取 128 两者都变白，取 160 两者都变黑，只有恰好落在两者之间才行，
 * 而这个区间每帧都在变。
 */
enum class OcrPreprocessMode(
    /** 设置界面展示的名称 */
    val label: String,
    /** 设置界面里的补充说明 */
    val description: String
) {
    /**
     * 只做灰度化（可选放大），保留原始明暗差异
     *
     * ML Kit 的识别模型是在自然图像上训练的，本身能处理低对比度；
     * 字幕与背景接近时，保留抗锯齿边缘反而比二值化更容易识别。
     */
    GRAYSCALE(
        "灰度（推荐）",
        "只转灰度、不做二值化，保留原图的明暗层次。字幕与背景颜色接近时用这个。"
    ),

    /**
     * 自适应阈值（Bradley）：按每个像素邻域的亮度单独取阈值
     *
     * 能适应画面局部明暗变化（一半亮场景一半暗场景），比固定阈值稳，
     * 但仍然是二值化，文字与背景在局部也接近时依然会糊在一起。
     */
    ADAPTIVE(
        "自适应二值化",
        "按局部亮度分别取阈值，适应画面明暗不均。文字与背景局部对比很低时仍可能失效。"
    ),

    /** 固定阈值二值化：全局一个阈值，原实现，保留用于对比 */
    FIXED(
        "固定阈值二值化",
        "全图用同一个阈值，字幕与背景亮度接近时会把两者处理成同色、文字消失。"
    );

    companion object {
        /** 默认方式：不做二值化 */
        val DEFAULT = GRAYSCALE

        /** 按枚举名还原，无法识别时返回 [DEFAULT]（兼容旧版本存的设置） */
        fun fromName(name: String?): OcrPreprocessMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
