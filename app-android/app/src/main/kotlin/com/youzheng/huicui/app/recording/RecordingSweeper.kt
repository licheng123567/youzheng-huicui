package com.youzheng.huicui.app.recording

import android.content.Context
import android.util.Log

/**
 * 兜底增量扫描（PRD §3.2 兜底层）。在 App 每次进前台时跑一次。
 *
 * 存在的理由：`FileObserver` 在国产 ROM 上会被静默杀掉，事件也会丢。
 * 用户打完电话把 App 划掉、过一小时再打开，录音必须还能被捡回来。
 */
object RecordingSweeper {

    private const val TAG = "RecordingSweeper"

    /** 只回看这么久内的通话；再老的会话即便冒出录音，也不敢往上挂。 */
    private const val LOOKBACK_MS = 6 * 3600_000L

    suspend fun sweep(context: Context) {
        if (!RecordingEnvironment.hasAllFilesAccess(context)) return
        val dir = RecordingEnvironment.detectRecordingDir() ?: return
        val repo = ServiceLocatorAccessor.recordingRepository() ?: return

        val sessions = repo.recentSessions(LOOKBACK_MS)
        if (sessions.isEmpty()) return

        val since = sessions.minOf { it.dialStartTs } - 60_000
        val candidates = RecordingScanner.scan(dir, since)
        if (candidates.isEmpty()) return

        val results = RecordingMatcher.matchAll(candidates, sessions.map { it.toDomain() })
        var enqueued = 0
        for (r in results) {
            when (r) {
                is MatchResult.Matched -> {
                    val dur = sessions.firstOrNull { it.callId == r.session.callId }?.durationSec
                    val outcome = CallOutcomeDecider.decide(dur, r.candidate)
                    if (outcome is CallOutcome.Uploadable) {
                        repo.enqueue(
                            caseId = r.session.caseId,
                            callId = r.session.callId,
                            candidate = r.candidate,
                            durationSec = outcome.durationSec.takeIf { it > 0 },
                            phone = r.session.number,
                            source = RecordingWatchService.SOURCE_APP_AUTO,
                        )
                        repo.markSessionState(r.session.callId, "MATCHED")
                        enqueued++
                    }
                }
                is MatchResult.NeedsConfirmation -> PendingConfirmations.add(r.candidate, r.sessions)
                is MatchResult.Unrelated -> Unit
            }
        }
        if (enqueued > 0) {
            Log.i(TAG, "兜底扫描补捞到 $enqueued 条录音")
            UploadScheduler.enqueueNow(context)
        }
    }
}

/** 让 sweeper 不直接依赖 ServiceLocator 的初始化时序（Worker 里可能还没 init）。 */
internal object ServiceLocatorAccessor {
    fun recordingRepository(): RecordingRepository? =
        runCatching { com.youzheng.huicui.app.ServiceLocator.recordingRepository }.getOrNull()
}
