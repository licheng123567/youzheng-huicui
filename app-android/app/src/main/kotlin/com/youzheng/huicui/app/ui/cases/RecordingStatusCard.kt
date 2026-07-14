package com.youzheng.huicui.app.ui.cases

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.CaseDetailMarkCodesInner
import com.youzheng.huicui.app.api.models.LatestRecording
import com.youzheng.huicui.app.recording.ManualUpload
import com.youzheng.huicui.app.recording.UploadScheduler
import com.youzheng.huicui.app.ui.common.Pill
import com.youzheng.huicui.app.ui.theme.huicui
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「获取最新通话录音」（BR-M4-01b）。四种状态要让人一眼看懂：
 * 无录音 / 上传中 / 解析中 / 已就绪。
 *
 * 无录音时给**手动上传**救济（BR-APP-04）：从系统文件选择器挑一个音频，
 * 走同一条解析链路，只是 `source=MANUAL`。
 */
@Composable
fun RecordingStatusCard(
    caseId: String,
    markCodes: List<CaseDetailMarkCodesInner> = emptyList(),
    canMark: Boolean = false,
    isHolder: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf<LatestRecording?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val msg = ManualUpload.enqueueFromUri(context, caseId, uri)
            busy = false
            error = msg
            if (msg == null) {
                UploadScheduler.enqueueNow(context)
                refreshKey++
            }
        }
    }

    // 取一次，然后**只要还在转写就接着轮**：ASR 是异步的，几十秒到几分钟不等。
    //
    // 取数和轮询必须在同一个 effect 里，且**不能拿 status 当 key**：
    //   · 拿 status 当 key —— 轮一轮回来状态多半还是 PARSING，key 没变，effect 不重启，
    //     于是只轮了一次就永久停摆，卡片卡在「转写解析中…」，底下的 AI 复盘也永远等不到 READY。
    //   · 拆成两个 effect —— 轮询那个先跑，此时 latest 还是 null，状态判空直接退出，同样一轮都不轮。
    // 跑到终态（READY/FAILED/QUOTA_BLOCKED）循环自然结束。
    LaunchedEffect(caseId, refreshKey) {
        error = null
        ServiceLocator.recordingRepository.latest(caseId)
            .onSuccess { latest = it }
            .onFailure { error = it.message }

        while (latest?.recording?.status?.value in TRANSIENT_STATUSES) {
            delay(10_000)
            ServiceLocator.recordingRepository.latest(caseId).onSuccess { latest = it }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近一通录音", style = MaterialTheme.typography.titleMedium)

            val l = latest
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)

                l == null -> Text("查询中…", style = MaterialTheme.typography.bodySmall)

                l.hasRecording != true -> {
                    Text(
                        l.hint ?: "未检测到本机录音",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "可能是系统「通话自动录音」没开，或本次未接通。也可以手动选一个录音文件上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                else -> {
                    val r = l.recording!!
                    val st = r.status?.value
                    // 能查到这条 recording，就说明**上传这一步已经成功**。此前主状态只有一行、副行是「自动回传」，
                    // 「回传」像进行时，加上没配 AI 时 PARSING 永远停着，用户就以为「一直在上传」。
                    // 拆成两段：①上传（已完成✓）②解析（当前态），并去掉「回传」歧义。
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("已上传", huicui.success)
                        Pill(statusText(st), recordingStatusColor(st))
                    }
                    Text(
                        buildString {
                            append(if (r.source?.value == "APP_AUTO") "自动录音" else "手动上传")
                            r.durationSec?.let { append(" · 通话 ${it}s") }
                            r.phone?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    // 诚实说明「解析中」为何可能长时间不动：转写靠平台 AI，未开启时停在此态属正常，录音本身已存好。
                    if (st == "PARSING" || st == "UPLOADED") {
                        Text(
                            "转写与 AI 复盘由平台完成；平台未开启转写时会停在这一步，不影响录音已保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    r.failureMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 「最近一通录音」已经传上来了，就不再把「手动上传」摆成主按钮 —— 那会诱导重复上传同一通。
            //
            // 但**不能因此把这条路彻底堵死**：latest 只反映最近一条录音。第二天再打一通、
            // 而这次系统没抓到录音（未接通误判、ROM 改了录音目录、权限被撤……），
            // latest 返回的仍是昨天那条 READY，按钮一藏，用户就再也没有补传今天这通的入口了 ——
            // 而 BR-APP-04 的手动上传本就是为这种时候准备的救济手段。
            //
            // 所以：已传成功时，主按钮收起，但保留一个次要入口「补传其他通话录音」。
            val noUsableRecording = latest?.let {
                it.hasRecording != true || it.recording?.status?.value == "FAILED"
            } ?: false
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { refreshKey++ }, enabled = !busy) { Text("刷新") }
                if (noUsableRecording) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        enabled = !busy,
                    ) { Text(if (busy) "处理中…" else "手动上传录音") }
                } else {
                    TextButton(
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        enabled = !busy,
                    ) { Text(if (busy) "处理中…" else "补传其他通话录音") }
                }
            }

            // 有录音才谈得上复盘与标记：两者都挂在 recordingId 上。
            latest?.recording?.id?.takeIf { it.isNotBlank() }?.let { rid ->
                HorizontalDivider()
                AiReviewSection(
                    recordingId = rid,
                    recordingStatus = latest?.recording?.status?.value,
                    markCodes = markCodes,
                    canMark = canMark,
                    isHolder = isHolder,
                )
            }
        }
    }
}

/** 还在流转、值得继续轮询的状态。到 READY/FAILED/QUOTA_BLOCKED 就别再问了。 */
private val TRANSIENT_STATUSES = setOf("UPLOADED", "PARSING")

/** 「已上传」由前一个绿徽标说了，这里只说**转写**走到哪一步，别重复。 */
@Composable
private fun recordingStatusColor(status: String?) = when (status) {
    "READY" -> huicui.success
    "FAILED" -> huicui.danger
    "QUOTA_BLOCKED" -> huicui.warning
    else -> MaterialTheme.colorScheme.primary
}

private fun statusText(status: String?): String = when (status) {
    "UPLOADED" -> "等待转写"
    "PARSING" -> "转写解析中…"
    "READY" -> "已就绪 · 可看 AI 复盘"
    "QUOTA_BLOCKED" -> "余额不足，暂停转写"
    "FAILED" -> "解析失败，可重试"
    null -> "-"
    else -> status
}
