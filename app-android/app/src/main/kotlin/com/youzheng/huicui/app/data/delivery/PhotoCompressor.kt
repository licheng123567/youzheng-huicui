package com.youzheng.huicui.app.data.delivery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把相机/相册给的 content Uri 落成待上传的 JPEG 文件。决策逻辑在 [ImageShrink]（纯函数，有单测），
 * 这里只是 Bitmap 搬运工。
 *
 * 产出写进 cacheDir/delivery：系统随时可清、卸载即消，不申请任何存储权限。
 * 送达照片**上传成功后本地即弃**——真相在服务端，App 不做照片的离线队列
 * （录音必须做队列是因为录音只有本机有；照片是现场拍的，失败了再拍一张就是）。
 */
class PhotoCompressor(private val context: Context) {

    suspend fun prepare(uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // 先只读尺寸，决定采样率——整图解码 4000px 原片可能直接 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("读不到照片")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不是有效的图片" }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageShrink.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("解码失败")

        val dir = File(context.cacheDir, "delivery").apply { mkdirs() }
        val out = File(dir, "delivery-${System.currentTimeMillis()}.jpg")
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, ImageShrink.JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
        check(out.length() in 1 until ImageShrink.MAX_UPLOAD_BYTES) { "压缩后仍超 20MB，无法上传" }
        out
    }
}
