package com.youzheng.huicui.app.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 通话起止信号（附录 A.5a 辅信号）。
 *
 * 只做两件事：
 *   · `OFFHOOK`：通话开始。**不代表对方接听** —— 外呼一拨出就 OFFHOOK。
 *   · `IDLE`：通话结束 → 拉起 [RecordingWatchService] 去找录音。
 *
 * 接通与否绝不在这里判，交给 [CallLogReader] 的 duration（附录 A.5a）。
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                lastOffHookTs = System.currentTimeMillis()
                Log.d(TAG, "OFFHOOK（注意：这不代表对方接听）")
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                // 没有 OFFHOOK 就直接 IDLE：多半是别人打进来的未接来电，与我们的去电无关
                if (lastOffHookTs == 0L) return
                lastOffHookTs = 0L
                Log.d(TAG, "IDLE：通话结束，启动录音检测")
                RecordingWatchService.startAfterCall(context)
            }
        }
    }

    private companion object {
        const val TAG = "CallStateReceiver"

        @Volatile
        var lastOffHookTs: Long = 0
    }
}
