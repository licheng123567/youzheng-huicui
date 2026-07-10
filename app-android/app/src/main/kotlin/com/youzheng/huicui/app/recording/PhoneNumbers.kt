package com.youzheng.huicui.app.recording

/**
 * 号码归一化（BR-M4-01b 匹配的前提）。
 *
 * 录音文件名里的号码形态千奇百怪：`+8613900000099`、`086-139 0000 0099`、`13900000099`、
 * 甚至带联系人名 `张三(13900000099)`。案件里存的是裸号。不归一化就永远匹配不上。
 *
 * 归一化到「最后 11 位数字」而非简单去前缀：
 *   · 国内手机号恒为 11 位，`+86`/`086`/`0086` 前缀去掉后剩的就是它；
 *   · 座机（`010-12345678` → 11 位 `01012345678`）也刚好落在 11 位，可直接比；
 *   · 短号/服务号不足 11 位时**原样保留**，绝不左填充——否则 `10086` 会和别的号撞。
 */
object PhoneNumbers {

    /** 只保留数字。 */
    private fun digits(raw: String): String = raw.filter { it.isDigit() }

    /**
     * @return 归一化后的号码；无数字则返回空串（调用方须当作「解析不出号码」处理，退化为纯时间窗匹配）。
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var d = digits(raw)
        if (d.isEmpty()) return ""

        // 去国际前缀：0086 / 86 / 086。只在去掉之后仍 >= 11 位时才去，
        // 否则 "8613800" 这种短号会被误砍。
        for (prefix in listOf("0086", "086", "86")) {
            if (d.startsWith(prefix) && d.length - prefix.length >= 11) {
                d = d.substring(prefix.length)
                break
            }
        }
        return if (d.length > 11) d.takeLast(11) else d
    }

    /**
     * 两个号码是否指同一个人。
     * 任一为空 → false（「不知道」不等于「相同」；宁可退化为时间窗匹配，也不要错挂案件）。
     */
    fun sameNumber(a: String?, b: String?): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return na.isNotEmpty() && na == nb
    }
}
