package com.youzheng.huicui.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.youzheng.huicui.app.R
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.data.db.CallSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 通话结束后的录音捕获窗口（PRD §3.2 主层 + 兜底层）。
 *
 * **只在「通话结束 → 匹配 → 入队」这段窗口期活着**（默认 120s）。
 * 不做 7×24 常驻监听：那既费电，又是国产 ROM 的头号杀进程目标，PRD §3.2 已明确排除。
 *
 * 两路信号并行：
 *   · 主：`FileObserver` 听 `CLOSE_WRITE` 与 `MOVED_TO`（很多 ROM 先写临时文件再重命名，
 *     只听 CLOSE_WRITE 会漏）。
 *   · 兜底：窗口内周期性增量扫描目录。FileObserver 在部分 ROM 上会被静默杀死或丢事件。
 */
class RecordingWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: FileObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val windowMs = intent?.getLongExtra(EXTRA_WINDOW_MS, DEFAULT_WINDOW_MS) ?: DEFAULT_WINDOW_MS
        scope.launch { watch(windowMs) }
        return START_NOT_STICKY
    }

    private suspend fun watch(windowMs: Long) {
        val dir = RecordingEnvironment.detectRecordingDir() ?: RecordingEnvironment.firstExistingDir()
        if (dir == null) {
            Log.w(TAG, "未定位到录音目录，放弃本次检测")
            stopSelf()
            return
        }

        val repo = ServiceLocator.recordingRepository
        val startedAt = System.currentTimeMillis()

        // 补齐通话结束时刻与时长（主信号：CallLog）
        repo.openSession()?.let { s ->
            val dur = CallLogReader.outgoingDurationSec(applicationContext, s.number, s.dialStartTs)
            repo.endCall(s.callId, dur)
        }

        val hits = java.util.concurrent.ConcurrentLinkedQueue<String>()
        observer = newObserver(dir) { name -> hits.add(File(dir, name).absolutePath) }
        observer?.startWatching()

        // 窗口期内：消费 FileObserver 事件 + 周期兜底扫描
        // 扫描起点比通话开始稍早，避免 ROM 用「拨号瞬间」给文件命名而被漏掉
        val scanSince = (repo.recentSessions().minOfOrNull { it.dialStartTs } ?: startedAt) - 60_000
        var elapsed = 0L
        var handledAny = false
        while (elapsed < windowMs) {
            while (true) {
                val path = hits.poll() ?: break
                if (handleCandidate(path)) handledAny = true
            }
            RecordingScanner.scan(dir, scanSince).forEach { c ->
                if (handleCandidate(c.path)) handledAny = true
            }
            if (handledAny) break   // 拿到就走，别在后台空耗
            delay(POLL_MS)
            elapsed += POLL_MS
        }

        if (!handledAny) {
            // 接通了却没录音 → 这是要引导用户的重点场景（多半是系统自动录音没开）
            repo.recentSessions(windowMs = 10 * 60_000).firstOrNull { it.callEndTs != null }?.let { s ->
                val outcome = CallOutcomeDecider.decide(s.durationSec, null)
                val state = when (outcome) {
                    is CallOutcome.NotConnected -> "NOT_CONNECTED"
                    is CallOutcome.RecordingMissing -> "RECORDING_MISSING"
                    else -> "UNRESOLVED"
                }
                repo.markSessionState(s.callId, state)
                Log.i(TAG, "窗口结束仍无录音：$state")
            }
        }

        observer?.stopWatching()
        stopSelf()
    }

    /** @return 是否成功入队 */
    private suspend fun handleCandidate(path: String): Boolean {
        if (!RecordingFileName.isSupportedAudio(File(path).name)) return false
        val repo = ServiceLocator.recordingRepository

        // 半截文件不入队：等大小稳定
        val candidate = RecordingScanner.awaitStable(path) ?: return false

        val sessions = repo.recentSessions().map { it.toDomain() }
        return when (val r = RecordingMatcher.match(candidate, sessions)) {
            is MatchResult.Matched -> {
                val s = r.session
                val outcome = CallOutcomeDecider.decide(
                    repo.recentSessions().firstOrNull { it.callId == s.callId }?.durationSec,
                    candidate,
                )
                if (outcome is CallOutcome.Uploadable) {
                    repo.enqueue(
                        caseId = s.caseId,
                        callId = s.callId,
                        candidate = candidate,
                        durationSec = outcome.durationSec.takeIf { it > 0 },
                        phone = s.number,
                        source = SOURCE_APP_AUTO,
                        recordedAtMillis = candidate.lastModified,
                    )
                    repo.markSessionState(s.callId, "MATCHED")
                    UploadScheduler.enqueueNow(applicationContext)
                    Log.i(TAG, "录音已匹配并入队：${r.reason}")
                    true
                } else {
                    false
                }
            }

            is MatchResult.NeedsConfirmation -> {
                // 宁可问不可错挂：不入队，交给「上传队列」页让 CO 二选一
                Log.i(TAG, "录音归属不明，等待用户确认：${r.reason}")
                PendingConfirmations.add(candidate, r.sessions)
                false
            }

            is MatchResult.Unrelated -> false
        }
    }

    private fun newObserver(dir: String, onFile: (String) -> Unit): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(dir), mask) {
                override fun onEvent(event: Int, path: String?) { path?.let(onFile) }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) { path?.let(onFile) }
            }
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "通话录音检测", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在检测通话录音")
            .setContentText("通话结束后短暂运行，完成即退出")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        observer?.stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingWatch"
        private const val CHANNEL_ID = "recording_watch"
        private const val NOTIF_ID = 1001
        private const val EXTRA_WINDOW_MS = "windowMs"
        private const val DEFAULT_WINDOW_MS = 120_000L
        private const val POLL_MS = 3_000L

        const val SOURCE_APP_AUTO = "APP_AUTO"
        const val SOURCE_MANUAL = "MANUAL"

        fun startAfterCall(context: Context, windowMs: Long = DEFAULT_WINDOW_MS) {
            if (!RecordingEnvironment.hasAllFilesAccess(context)) return
            val i = Intent(context, RecordingWatchService::class.java).putExtra(EXTRA_WINDOW_MS, windowMs)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}

internal fun CallSessionEntity.toDomain() = CallSession(callId, caseId, number, dialStartTs, callEndTs)

/** 归属不明的录音，暂存在内存 + 落 UI 供用户二选一（进程被杀后由兜底扫描重新发现）。 */
object PendingConfirmations {
    private val items = mutableListOf<Pair<RecordingCandidate, List<CallSession>>>()

    @Synchronized
    fun add(c: RecordingCandidate, sessions: List<CallSession>) {
        if (items.none { it.first.path == c.path }) items += c to sessions
    }

    @Synchronized
    fun all(): List<Pair<RecordingCandidate, List<CallSession>>> = items.toList()

    @Synchronized
    fun remove(path: String) { items.removeAll { it.first.path == path } }

    @Synchronized
    fun clear() = items.clear()
}
