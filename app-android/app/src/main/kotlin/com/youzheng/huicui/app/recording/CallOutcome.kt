package com.youzheng.huicui.app.recording

/**
 * 接通判定与「该不该上传」（BR-APP-05 / 附录 A.5a 三信号交叉）。
 *
 * 三个信号，可靠性从高到低：
 *   1. `CallLog.Calls.duration`（READ_CALL_LOG）—— **主信号**。>0 基本等于接通。
 *   2. 是否出现录音文件 —— ROM 只在接通后才录，出现录音是接通的强证据。
 *   3. TelephonyCallback 的 OFFHOOK —— **不可用于判接通**：外呼一拨出就 OFFHOOK，
 *      对方没接也会触发。只用来标通话起止、触发去找录音。
 *
 * 未接通（duration≈0 且无录音）**不上传**：既不浪费 ASR 分钟（BR-M5-01 按分钟计费），
 * 也不会产生一条毫无内容的转写去污染话术飞轮。
 */
sealed interface CallOutcome {
    /** 接通且有录音 → 入上传队列。 */
    data class Uploadable(val durationSec: Int, val candidate: RecordingCandidate) : CallOutcome

    /** 未接通（对方没接/占线/拒接）→ 只留本地通话记录，不上传。 */
    data class NotConnected(val reason: String) : CallOutcome

    /** 接通了但录音没出现 → 提示手动上传（BR-APP-04 手动救济）。 */
    data class RecordingMissing(val durationSec: Int) : CallOutcome
}

object CallOutcomeDecider {

    /** duration 小于此值视为未接通（响一声就挂、彩铃期间挂断，系统偶尔记 1s）。 */
    const val MIN_CONNECTED_SEC = 2

    /**
     * @param callLogDurationSec CallLog 查到的时长；查不到（无权限/未落库）传 null。
     * @param candidate 已匹配到的录音；没有传 null。
     */
    fun decide(callLogDurationSec: Int?, candidate: RecordingCandidate?): CallOutcome {
        // 有录音文件：ROM 只在接通后录音，这是比 CallLog 更硬的证据。
        // 即便 CallLog 读不到（用户拒了 READ_CALL_LOG），也应上传。
        if (candidate != null) {
            val dur = callLogDurationSec?.takeIf { it > 0 } ?: 0
            return CallOutcome.Uploadable(dur, candidate)
        }

        return when {
            callLogDurationSec == null ->
                // 既没录音也读不到通话记录：无法断言未接通，也无从上传。
                CallOutcome.NotConnected("未检测到录音，且无法读取通话记录（缺 READ_CALL_LOG 权限？）")

            callLogDurationSec < MIN_CONNECTED_SEC ->
                CallOutcome.NotConnected("通话时长 ${callLogDurationSec}s，判定未接通")

            else ->
                // 接通了却没录音：多半是系统自动录音没开，或目录选错了。这是要引导用户的重点场景。
                CallOutcome.RecordingMissing(callLogDurationSec)
        }
    }
}
