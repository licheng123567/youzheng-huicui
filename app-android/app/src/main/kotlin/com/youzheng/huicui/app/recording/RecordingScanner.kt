package com.youzheng.huicui.app.recording

import kotlinx.coroutines.delay
import java.io.File

/**
 * 目录扫描 + 文件稳定性等待。
 *
 * 稳定性等待是必需的：多数 ROM 先写临时文件再重命名，`FileObserver` 的 `CLOSE_WRITE`
 * 也可能在缓冲刷盘前就到。半截音频传上去，服务端 ASR 只会返回 FAILED，
 * 而按 BR-M9-08 的口径**失败也算消耗** —— 白花钱还得让催收员重传。
 */
object RecordingScanner {

    private const val SAMPLE_INTERVAL_MS = 1_000L
    private const val MAX_WAIT_MS = 60_000L

    /** 列出目录下、在 [since] 之后修改过的、可识别音频文件。 */
    fun scan(dir: String, since: Long): List<RecordingCandidate> {
        val d = File(dir)
        if (!d.isDirectory) return emptyList()
        return d.listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() >= since && RecordingFileName.isSupportedAudio(it.name) }
            .map { RecordingCandidate(it.absolutePath, it.name, it.lastModified(), it.length()) }
            .sortedBy { it.lastModified }
    }

    /**
     * 等到文件大小连续若干次采样不变（PRD §3.2「大小连续 N 秒不变才入队」）。
     * @return 稳定后的候选；超时仍在增长则返回 null（下一轮兜底扫描会再遇到它）。
     */
    suspend fun awaitStable(path: String): RecordingCandidate? {
        val f = File(path)
        val samples = mutableListOf<Long>()
        var waited = 0L
        while (waited < MAX_WAIT_MS) {
            if (!f.exists()) return null
            samples += f.length()
            if (UploadPolicy.isFileStable(samples)) {
                return RecordingCandidate(f.absolutePath, f.name, f.lastModified(), f.length())
            }
            delay(SAMPLE_INTERVAL_MS)
            waited += SAMPLE_INTERVAL_MS
        }
        return null
    }
}
