package com.youzheng.huicui.app.recording

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 从系统录音文件名里抠出「号码」与「录制时间」。纯函数，可离线单测。
 *
 * PRD §3.1 列了各 ROM 的文件名模式。但**文件名解析永远是启发式的**：
 * ROM 大版本一改就漂移，用户改过联系人名也会变。所以这里的契约是：
 *   · 抠得出就返回，抠不出返回 null —— **绝不猜**。
 *   · 上层（RecordingMatcher）在号码抠不出时退化为纯时间窗匹配（PRD §3.3 退化规则）。
 *   · 文件的 `lastModified` 永远是可信兜底，文件名时间只是更精确的辅助信号。
 */
data class ParsedRecordingName(
    val phone: String?,
    /** 文件名里解析出的录制时刻（epoch millis，设备本地时区）；解析不出为 null。 */
    val recordedAtMillis: Long?,
)

object RecordingFileName {

    /** 连续 11 位数字（国内手机号）；两侧不能再是数字，避免从长时间戳里截出「号码」。 */
    private val PHONE_RE = Regex("(?<![0-9])(1[3-9][0-9]{9})(?![0-9])")

    /** 带国际前缀的形态：+8613900000099 / 008613900000099 */
    private val INTL_PHONE_RE = Regex("(?<![0-9])(?:\\+?0{0,2}86)(1[3-9][0-9]{9})(?![0-9])")

    /**
     * 时间戳模式，按「信息量从多到少」尝试。
     * 注意顺序：yyyyMMddHHmmss 必须排在 yyyyMMdd 前面，否则 14 位串会被前 8 位吃掉。
     */
    private val TIME_PATTERNS = listOf(
        Regex("(20[0-9]{2}[01][0-9][0-3][0-9][0-2][0-9][0-5][0-9][0-5][0-9])") to "yyyyMMddHHmmss",
        Regex("(20[0-9]{2}-[01][0-9]-[0-3][0-9][ _][0-2][0-9]-[0-5][0-9]-[0-5][0-9])") to "yyyy-MM-dd HH-mm-ss",
        Regex("(20[0-9]{2}-[01][0-9]-[0-3][0-9][ _][0-2][0-9]:[0-5][0-9]:[0-5][0-9])") to "yyyy-MM-dd HH:mm:ss",
        Regex("(20[0-9]{2}_[01][0-9]_[0-3][0-9]_[0-2][0-9]_[0-5][0-9]_[0-5][0-9])") to "yyyy_MM_dd_HH_mm_ss",
        // 小米/HyperOS：通话录音_张三(139…)_20260709_10_30_00.mp3
        Regex("(20[0-9]{2}[01][0-9][0-3][0-9]_[0-2][0-9]_[0-5][0-9]_[0-5][0-9])") to "yyyyMMdd_HH_mm_ss",
    )

    fun parse(fileName: String, zone: ZoneId = ZoneId.systemDefault()): ParsedRecordingName {
        val base = fileName.substringBeforeLast('.')
        return ParsedRecordingName(
            phone = extractPhone(base),
            recordedAtMillis = extractTime(base, zone),
        )
    }

    private fun extractPhone(base: String): String? {
        INTL_PHONE_RE.find(base)?.let { return it.groupValues[1] }
        PHONE_RE.find(base)?.let { return it.groupValues[1] }
        return null
    }

    private fun extractTime(base: String, zone: ZoneId): Long? {
        for ((re, pattern) in TIME_PATTERNS) {
            val m = re.find(base) ?: continue
            // 「yyyy-MM-dd HH-mm-ss」这类模式里日期与时间可能被 '_' 分隔，统一成空格再解析
            val text = if (pattern.startsWith("yyyy-MM-dd ")) m.groupValues[1].replace('_', ' ')
            else m.groupValues[1]
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
                    .atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // 该模式不匹配，继续试下一个
            }
        }
        return null
    }

    /** 是不是我们认得的音频格式。不认得的（如 ROM 专有封装）不入队，避免服务端 ASR 白跑一趟。 */
    fun isSupportedAudio(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    /** 与服务端 ASR（百炼 paraformer-8k-v2）能吃的容器对齐。 */
    val SUPPORTED_EXTENSIONS = setOf("m4a", "mp3", "aac", "amr", "wav", "3gp", "ogg", "opus")
}
