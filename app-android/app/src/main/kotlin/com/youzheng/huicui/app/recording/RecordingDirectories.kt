package com.youzheng.huicui.app.recording

/**
 * 各 ROM 的系统通话录音目录预设（PRD §3.1）。
 *
 * ⚠️ 路径**随 ROM 大版本漂移**，本表只是「按优先级探测的候选集」，不是真值。
 * 真值由自检确定：能在候选目录里找到录音文件，才算命中；一个都不命中就让用户手动选目录。
 * 因此这里多列几个过时路径没有代价，漏列才有代价。
 */
data class OemProfile(
    val vendorKey: String,
    val displayName: String,
    /** 相对 /storage/emulated/0/ 的候选目录，按优先级 */
    val candidateDirs: List<String>,
    /** 系统设置里开启「通话自动录音」的路径提示 */
    val settingsHint: String,
    /** 该 ROM 是否已知具备系统通话录音能力（PRD §1.3 矩阵） */
    val hasSystemCallRecording: Boolean = true,
)

object RecordingDirectories {

    /** 通用兜底：任何机型都会额外扫这些目录（PRD §3.1 末行）。 */
    val FALLBACK_DIRS = listOf(
        "Recordings",
        "Recordings/Call",
        "Music/Recordings",
        "Record",
        "Sounds",
    )

    private val PROFILES = listOf(
        OemProfile(
            "huawei", "华为 EMUI / HarmonyOS",
            listOf("Sounds/CallRecord", "record", "Record"),
            "拨号盘 → 设置 → 通话自动录音",
        ),
        OemProfile(
            "honor", "荣耀 MagicOS",
            listOf("Sounds/CallRecord", "Record"),
            "拨号盘 → 设置 → 通话自动录音",
        ),
        OemProfile(
            "xiaomi", "小米 / 红米（MIUI / HyperOS）",
            listOf("MIUI/sound_recorder/call_rec", "Recordings/call_rec", "MIUI/sound_recorder"),
            "电话 → 右上角设置 → 通话录音 → 自动录音",
        ),
        OemProfile(
            "redmi", "红米（MIUI / HyperOS）",
            listOf("MIUI/sound_recorder/call_rec", "Recordings/call_rec"),
            "电话 → 右上角设置 → 通话录音 → 自动录音",
        ),
        OemProfile(
            "oppo", "OPPO ColorOS",
            listOf("Music/Recordings/Call Recordings", "Recordings", "Record/PhoneRecord"),
            "电话 → 设置 → 通话录音",
        ),
        OemProfile(
            "oneplus", "一加 ColorOS",
            listOf("Music/Recordings/Call Recordings", "Recordings"),
            "电话 → 设置 → 通话录音",
        ),
        OemProfile(
            "realme", "realme ColorOS",
            listOf("Music/Recordings/Call Recordings", "Recordings"),
            "电话 → 设置 → 通话录音",
        ),
        OemProfile(
            "vivo", "vivo / iQOO OriginOS",
            listOf("Record/Call", "录音/通话录音", "Record"),
            "电话 → 设置 → 通话录音",
        ),
        OemProfile(
            "iqoo", "iQOO OriginOS",
            listOf("Record/Call", "Record"),
            "电话 → 设置 → 通话录音",
        ),
        OemProfile(
            "samsung", "三星 One UI（国行）",
            listOf("Recordings/Call", "Sounds"),
            "电话 → 设置 → 录制通话（部分海外版本已被禁用）",
        ),
        OemProfile(
            "google", "Pixel / 原生 Android",
            emptyList(),
            "原生 Android 不提供系统通话录音",
            hasSystemCallRecording = false,
        ),
    )

    /** @param manufacturer 取自 `Build.MANUFACTURER`，大小写不敏感。 */
    fun profileFor(manufacturer: String?): OemProfile {
        val key = manufacturer.orEmpty().trim().lowercase()
        PROFILES.firstOrNull { it.vendorKey == key }?.let { return it }
        // 未知厂商：不假装认识它，但仍给全量兜底目录，让自检去试
        return OemProfile(
            vendorKey = key.ifEmpty { "unknown" },
            displayName = manufacturer.orEmpty().ifEmpty { "未知机型" },
            candidateDirs = emptyList(),
            settingsHint = "请在系统「电话」应用的设置里查找「通话录音」并开启",
        )
    }

    /** 该机型要探测的全部目录，去重且保序：厂商候选在前，通用兜底在后。 */
    fun candidatesFor(manufacturer: String?): List<String> =
        (profileFor(manufacturer).candidateDirs + FALLBACK_DIRS).distinct()
}
