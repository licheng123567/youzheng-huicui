package com.youzheng.huicui.app.recording

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.youzheng.huicui.app.ServiceLocator
import java.io.File
import java.io.IOException

/**
 * 手动上传救济（BR-APP-04）：自动检测拿不到录音时，让 CO 从文件选择器挑一个音频。
 * 走同一条上传/解析链路，只是 `source=MANUAL`。
 *
 * SAF 给的是 `content://` URI，不能直接当文件路径用；先复制进 App 私有目录再入队。
 */
object ManualUpload {

    /** @return null 表示成功；否则是给用户看的失败原因。 */
    suspend fun enqueueFromUri(context: Context, caseId: String, uri: Uri): String? {
        val name = displayName(context, uri) ?: "manual-upload"
        if (!RecordingFileName.isSupportedAudio(name)) {
            return "不支持的音频格式（可用：${RecordingFileName.SUPPORTED_EXTENSIONS.joinToString("/")}）"
        }

        val tmp = File(context.cacheDir, "manual-$name")
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: return "无法读取所选文件"
        } catch (e: IOException) {
            return "读取文件失败：${e.javaClass.simpleName}"
        }

        if (tmp.length() == 0L) {
            tmp.delete()
            return "所选文件是空的"
        }

        val candidate = RecordingCandidate(tmp.absolutePath, name, tmp.lastModified(), tmp.length())
        ServiceLocator.recordingRepository.enqueue(
            caseId = caseId,
            callId = null,          // 手动上传没有通话会话锚点
            candidate = candidate,
            durationSec = null,     // 由服务端 ASR 自己算
            phone = null,
            source = RecordingWatchService.SOURCE_MANUAL,
        )
        // enqueue 已把内容复制进私有目录，缓存里的临时文件不再需要
        tmp.delete()
        return null
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
