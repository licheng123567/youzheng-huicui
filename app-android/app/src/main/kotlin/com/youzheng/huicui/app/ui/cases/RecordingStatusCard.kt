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
import com.youzheng.huicui.app.api.models.LatestRecording
import com.youzheng.huicui.app.recording.ManualUpload
import com.youzheng.huicui.app.recording.UploadScheduler
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
fun RecordingStatusCard(caseId: String) {
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

    LaunchedEffect(caseId, refreshKey) {
        error = null
        ServiceLocator.recordingRepository.latest(caseId)
            .onSuccess { latest = it }
            .onFailure { error = it.message }
    }

    // 解析中就轮询：ASR 是异步的，几十秒到几分钟不等
    LaunchedEffect(latest?.recording?.status) {
        val st = latest?.recording?.status?.value
        if (st == "PARSING" || st == "UPLOADED") {
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
                    Text(statusText(r.status?.value), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append(if (r.source?.value == "APP_AUTO") "自动回传" else "手动上传")
                            r.durationSec?.let { append(" · 通话 ${it}s") }
                            r.phone?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    r.failureMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { refreshKey++ }, enabled = !busy) { Text("刷新") }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("audio/*")) },
                    enabled = !busy,
                ) { Text(if (busy) "处理中…" else "手动上传录音") }
            }
        }
    }
}

private fun statusText(status: String?): String = when (status) {
    "UPLOADED" -> "已上传，等待解析"
    "PARSING" -> "解析中…"
    "READY" -> "已就绪，可查看转写与 AI 复盘"
    "FAILED" -> "解析失败"
    null -> "-"
    else -> status
}
