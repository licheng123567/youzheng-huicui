package com.youzheng.huicui.app.recording

import java.io.File
import java.security.MessageDigest

/**
 * 文件内容哈希。**同时充当三个角色**：
 *   1. 本地去重（同一文件被兜底扫描重复发现时不会入队两次）；
 *   2. 上传队列主键；
 *   3. 发给服务端的 `Idempotency-Key` —— 重传同一文件时服务端返回 409「幂等键重放」，
 *      按 PRD 视为成功，绝不会重复扣 ASR 分钟、重复解析。
 *
 * 用 SHA-256 而非 MD5：碰撞代价是「录音被误判为已上传而永远丢失」，不值得省这点 CPU。
 */
object FileHash {

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
