package com.youzheng.huicui.app.recording

import java.time.ZoneId
import kotlin.math.abs

/** 拨号前落库的本地通话会话（BR-APP-03：这是录音↔案件匹配的唯一锚点）。 */
data class CallSession(
    val callId: String,
    val caseId: String,
    val number: String,
    val dialStartTs: Long,
    /** 通话结束（IDLE）时刻；尚未结束为 null。 */
    val callEndTs: Long? = null,
)

/** 一个候选录音文件（已经过大小稳定性检查）。 */
data class RecordingCandidate(
    val path: String,
    val fileName: String,
    val lastModified: Long,
    val sizeBytes: Long,
)

sealed interface MatchResult {
    /** 唯一确定：可以直接入上传队列。 */
    data class Matched(val session: CallSession, val candidate: RecordingCandidate, val reason: String) : MatchResult

    /** 拿不准：宁可问用户，也不要错挂案件（PRD §3.3）。 */
    data class NeedsConfirmation(
        val candidate: RecordingCandidate,
        val sessions: List<CallSession>,
        val reason: String,
    ) : MatchResult

    /** 该文件不属于任何一通我们发起的通话（用户自己录的音、别的 App 的文件）。 */
    data class Unrelated(val candidate: RecordingCandidate, val reason: String) : MatchResult
}

data class MatchConfig(
    /**
     * 解析文件名时间戳所用的时区。**文件名里的时间是设备本地时间**，
     * 必须显式传入而不是隐式取 systemDefault —— 否则这段逻辑在测试里、
     * 以及用户出差改了手机时区之后，行为都会悄悄变。
     */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** 通话结束后仍可能落盘（ROM 写文件有延迟）：结束时刻 + buffer 作为窗口右界。PRD 默认 60s。 */
    val tailBufferMs: Long = 60_000,
    /** 通话尚未结束（callEndTs 为空）时，窗口右界取 dialStart + 该值。 */
    val openWindowMs: Long = 60 * 60_000,
    /** 文件时间比拨号时刻早多少以内仍算「同一通」（ROM 可能用拨号瞬间命名）。 */
    val headSlackMs: Long = 5_000,
)

/**
 * 录音 ↔ 案件匹配（PRD §3.3 / 附录 A.5b）。
 *
 * 判定顺序（有意如此）：
 *   1. **号码 + 时间窗**同时命中 → Matched（最强信号）。
 *   2. 号码解析不出（ROM 文件名里没有号码）→ 退化为**纯时间窗**：
 *      窗口内唯一 → Matched；窗口内多个会话 → NeedsConfirmation。
 *   3. 号码解析出来但与所有会话都不一致 → Unrelated（用户自己录的音，别碰）。
 *   4. 号码一致但落在多个会话的窗口里（连续拨打同一号码）→ 取时间就近者，
 *      **但若两者时间差在容忍度内难分伯仲 → NeedsConfirmation**。
 *
 * 「宁可问不可错挂」是这里的最高原则：录音一旦挂错案件，会污染另一案的转写、质检与存证，
 * 而这些是要拿去法务举证的。
 */
object RecordingMatcher {

    /** 两个会话与文件的时间距离差小于此值时，判为难分伯仲，交给用户确认。 */
    private const val AMBIGUOUS_DELTA_MS = 15_000L

    fun match(
        candidate: RecordingCandidate,
        sessions: List<CallSession>,
        config: MatchConfig = MatchConfig(),
    ): MatchResult {
        if (sessions.isEmpty()) return MatchResult.Unrelated(candidate, "没有任何待匹配的通话会话")

        val parsed = RecordingFileName.parse(candidate.fileName, config.zone)
        // 文件名里的时间比 lastModified 更贴近真实录制时刻（后者可能被复制/扫描改写）
        val fileTs = parsed.recordedAtMillis ?: candidate.lastModified

        val inWindow = sessions.filter { inTimeWindow(fileTs, it, config) }

        // ── 号码可解析：号码是最强信号 ──────────────────────────────────
        if (parsed.phone != null) {
            val sameNumber = sessions.filter { PhoneNumbers.sameNumber(parsed.phone, it.number) }
            if (sameNumber.isEmpty()) {
                return MatchResult.Unrelated(
                    candidate,
                    "文件名号码 ${parsed.phone} 与任何一通去电都不一致",
                )
            }
            val both = sameNumber.filter { inTimeWindow(fileTs, it, config) }
            return when {
                both.size == 1 -> MatchResult.Matched(both.first(), candidate, "号码一致且落在通话时间窗内")
                both.size > 1 -> pickNearestOrAsk(candidate, both, fileTs, "同号码连续拨打")
                // 号码对上了但时间窗没对上：可能是 ROM 写盘极慢，或用户改了系统时间。
                // 不硬判为无关——号码是强证据，但也不敢直接挂，交给用户。
                else -> MatchResult.NeedsConfirmation(
                    candidate, sameNumber, "号码一致但录制时间不在任何通话窗内",
                )
            }
        }

        // ── 号码解析不出：退化为纯时间窗（PRD §3.3 退化规则）──────────────
        return when {
            inWindow.isEmpty() -> MatchResult.Unrelated(candidate, "文件名无号码，且不在任何通话时间窗内")
            inWindow.size == 1 -> MatchResult.Matched(inWindow.first(), candidate, "文件名无号码，时间窗内唯一")
            else -> pickNearestOrAsk(candidate, inWindow, fileTs, "文件名无号码，时间窗内有多通通话")
        }
    }

    /** 批量：一个文件只能挂一通；一通也只挂一个文件（先到先得，按时间就近）。 */
    fun matchAll(
        candidates: List<RecordingCandidate>,
        sessions: List<CallSession>,
        config: MatchConfig = MatchConfig(),
    ): List<MatchResult> {
        val remaining = sessions.toMutableList()
        val results = mutableListOf<MatchResult>()
        // 先处理时间早的文件，保证连续拨打时的先后顺序稳定
        for (c in candidates.sortedBy { it.lastModified }) {
            val r = match(c, remaining, config)
            if (r is MatchResult.Matched) remaining.remove(r.session)
            results += r
        }
        return results
    }

    private fun inTimeWindow(fileTs: Long, s: CallSession, config: MatchConfig): Boolean {
        val start = s.dialStartTs - config.headSlackMs
        val end = (s.callEndTs?.plus(config.tailBufferMs)) ?: (s.dialStartTs + config.openWindowMs)
        return fileTs in start..end
    }

    private fun pickNearestOrAsk(
        candidate: RecordingCandidate,
        sessions: List<CallSession>,
        fileTs: Long,
        why: String,
    ): MatchResult {
        val sorted = sessions.sortedBy { distance(fileTs, it) }
        val best = sorted[0]
        val second = sorted[1]
        val gap = abs(distance(fileTs, best) - distance(fileTs, second))
        return if (gap >= AMBIGUOUS_DELTA_MS) {
            MatchResult.Matched(best, candidate, "$why：取时间就近者（相差 ${gap / 1000}s）")
        } else {
            MatchResult.NeedsConfirmation(candidate, sorted, "$why：两通时间过于接近，无法确定归属")
        }
    }

    /** 文件时间到该会话「通话区间」的距离；落在区间内为 0。 */
    private fun distance(fileTs: Long, s: CallSession): Long {
        val end = s.callEndTs ?: s.dialStartTs
        return when {
            fileTs < s.dialStartTs -> s.dialStartTs - fileTs
            fileTs > end -> fileTs - end
            else -> 0
        }
    }
}
