package com.youzheng.huicui.app.recording

/**
 * 把 SAF 目录选择器返回的 tree URI 还原成文件系统路径。
 *
 * 为什么需要这一步：用户手动选目录用的是系统目录选择器（体验好、路径可见），
 * 但真正读取录音、挂 `FileObserver` 走的是**全盘文件权限 + 绝对路径** ——
 * SAF 的 `DocumentFile` 挂不了 `FileObserver`，拿不到「通话一挂断就有文件」的实时信号。
 *
 * tree URI 形如：
 *   `content://com.android.externalstorage.documents/tree/primary%3ARecordings%2FCall`
 * decode 后是 `primary:Recordings/Call`，其中 `primary` = 内置存储。
 *
 * 外置 SD 卡（`XXXX-XXXX:` 前缀）不做支持：系统通话录音从不写到 SD 卡上，
 * 硬猜挂载点只会给出一个不存在的路径。返回 null，让界面明确告诉用户「请选内置存储里的目录」。
 */
object SafPaths {

    private const val EXTERNAL_ROOT = "/storage/emulated/0"

    /**
     * @param treeUriString `Uri.toString()` 的结果
     * @return 绝对路径；无法还原时返回 null
     */
    fun toFilePath(treeUriString: String?, externalRoot: String = EXTERNAL_ROOT): String? {
        if (treeUriString.isNullOrBlank()) return null
        val marker = "/tree/"
        val idx = treeUriString.indexOf(marker)
        if (idx < 0) return null

        val encoded = treeUriString.substring(idx + marker.length).substringBefore('/')
        val decoded = decode(encoded)
        val parts = decoded.split(':', limit = 2)
        if (parts.size != 2) return null

        val (volume, relative) = parts
        if (!volume.equals("primary", ignoreCase = true)) return null   // 只认内置存储

        return if (relative.isEmpty()) externalRoot else "$externalRoot/${relative.trimStart('/')}"
    }

    /**
     * 只需处理 %XX，不把 '+' 当空格（URI path 段里 '+' 就是加号）。
     *
     * **必须按 UTF-8 解字节**，不能逐个 `%XX` 直接 `toChar()`：
     * vivo 的录音目录叫「录音/通话录音」，UTF-8 下每个汉字三字节，
     * 逐字节转 char 会得到乱码，于是那个目录永远找不到。
     */
    private fun decode(s: String): String {
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            bytes.write(c.code)
            i++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
