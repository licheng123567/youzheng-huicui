package com.youzheng.huicui.app.data.delivery

/**
 * 照片压缩的**决策**部分：纯函数，JVM 可测。真正动 Bitmap 的是 [PhotoCompressor]（Android 侧薄壳）。
 *
 * 边界来自后端：`case_attachment.bytes` 是 BYTEA，AttachmentController 卡 20MB。
 * 手机原片普遍 3–12MB、4000px+，直传既慢又挤爆库。但这是**送达文书的照片**——
 * 压过头字就糊了，证据照片糊字等于白拍。长边 2560 / JPEG 85 是「文书上的字仍清晰可读」
 * 的下限档位，产出普遍 <2MB。
 */
object ImageShrink {

    /** 后端 AttachmentController.MAX_BYTES 的镜像。超过它后端直接 413/422，连重试的机会都没有。 */
    const val MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

    /** 压缩目标长边。低于它的图不放大（放大只会更糊）。 */
    const val MAX_EDGE: Int = 2560

    /** JPEG 质量档。 */
    const val JPEG_QUALITY: Int = 85

    /**
     * BitmapFactory 的 inSampleSize：2 的幂，选**不小于**目标的最大缩尺
     * （宁可解码得比 2560 大再靠 quality 收，不能一步跳到比 2560 小——那是二次损失）。
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        require(width > 0 && height > 0) { "非法尺寸 ${width}x$height" }
        var sample = 1
        var edge = maxOf(width, height)
        while (edge / 2 >= maxEdge) {
            sample *= 2
            edge /= 2
        }
        return sample
    }

    /** 尺寸和体积都已达标的图不需要重编码——重编码永远是画质损失。 */
    fun needsShrink(bytes: Long, width: Int, height: Int): Boolean =
        bytes > MAX_UPLOAD_BYTES || maxOf(width, height) > MAX_EDGE
}
