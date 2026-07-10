package com.youzheng.huicui.app.recording

import android.Manifest
import android.content.Context
import android.provider.CallLog
import kotlin.math.abs

/**
 * 接通/时长判定的**主信号**（附录 A.5a）：系统通话记录里的 `duration`。
 *
 * 为什么不能用 TelephonyCallback 的 OFFHOOK 判接通：**外呼一拨出就 OFFHOOK**，
 * 对方根本没接也会触发。用它判接通，会把所有「响了没人接」的通话都当成接通并上传，
 * 白白消耗 ASR 分钟，还往话术飞轮里灌满忙音。
 */
object CallLogReader {

    /** 通话记录落库有延迟；查询时以拨号时刻为中心开一个宽窗。 */
    private const val MATCH_WINDOW_MS = 5 * 60_000L

    /**
     * @return 该通去电的时长（秒）；无权限或查不到返回 null（**不是 0** —— 「查不到」与「未接通」是两回事）。
     */
    fun outgoingDurationSec(context: Context, number: String, dialStartTs: Long): Int? {
        if (!RecordingEnvironment.hasPermission(context, Manifest.permission.READ_CALL_LOG)) return null

        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
        val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?"
        val args = arrayOf(
            CallLog.Calls.OUTGOING_TYPE.toString(),
            (dialStartTs - MATCH_WINDOW_MS).toString(),
        )

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, args, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                var best: Pair<Long, Int>? = null   // (|date - dialStart|, duration)
                while (c.moveToNext()) {
                    if (!PhoneNumbers.sameNumber(c.getString(numIdx), number)) continue
                    val date = c.getLong(dateIdx)
                    val delta = abs(date - dialStartTs)
                    if (delta > MATCH_WINDOW_MS) continue
                    val dur = c.getInt(durIdx)
                    if (best == null || delta < best!!.first) best = delta to dur
                }
                best?.second
            }
        } catch (e: SecurityException) {
            // 权限被运行时撤销
            null
        }
    }
}
