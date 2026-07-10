package com.youzheng.huicui.app.recording

import android.content.Context
import java.io.File

/**
 * 上传前把录音复制进 App 私有目录，从副本上传。
 *
 * 两条原则：
 *
 * 1. **绝不删除、绝不修改系统录音目录里的文件。** 那是用户手机里的通话记录，
 *    不是我们的资产。PRD §3.4 说的「上传成功后清理本地录音」指的是**我们自己的副本**。
 *    （原文亦写明「本地录音副本落 App 私有目录」。）
 *
 * 2. **副本上传成功即删**，不留 7 天。服务端已存了音频（V921 `audio_bytes`）且 App 可流式回听，
 *    本地再留一份只是徒增泄露面。数据落地越少越好。
 *
 * 至于「副本加密存储」：本版**没有做**。私有目录在未 root 的设备上其他 App 读不到，
 * 而副本的生命周期通常只有几秒到几分钟（离线时可能几小时）。
 * 上 `EncryptedFile` 会让上传时必须先解密到临时明文文件，泄露面并没有真正减少。
 * 若将来要求「静态加密」，正确做法是流式加解密而不是落临时明文，届时再改。
 */
class LocalRecordingStore(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "recordings").apply { mkdirs() }
    }

    fun copyIn(source: File, fileHash: String): File {
        val ext = source.name.substringAfterLast('.', "bin")
        val dest = File(dir, "$fileHash.$ext")
        if (dest.exists() && dest.length() == source.length()) return dest
        source.inputStream().use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
        return dest
    }

    fun delete(path: String) {
        val f = File(path)
        // 只删自己私有目录里的东西。传进来一个系统录音路径也不会误删。
        if (f.parentFile?.absolutePath == dir.absolutePath) f.delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun isOurCopy(path: String): Boolean = File(path).parentFile?.absolutePath == dir.absolutePath
}
