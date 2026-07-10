package com.youzheng.huicui.app.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.data.db.UploadItemEntity
import com.youzheng.huicui.app.recording.CallSession
import com.youzheng.huicui.app.recording.PendingConfirmations
import com.youzheng.huicui.app.recording.RecordingCandidate
import com.youzheng.huicui.app.recording.RecordingWatchService
import com.youzheng.huicui.app.recording.UploadScheduler
import com.youzheng.huicui.app.recording.UploadStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上传队列（BR-APP-07：队列对用户可见可管）。
 *
 * 顶部先处理「归属不明」的录音 —— 匹配器拿不准时不会瞎挂案件，而是把选择权交回来。
 * 错挂一条录音会污染另一个案件的转写、质检与存证，而那些是要拿去法务举证的。
 */
@Composable
fun UploadQueueScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by ServiceLocator.recordingRepository.observeQueue().collectAsState(initial = emptyList())
    val pending = PendingConfirmations.all()

    LazyColumn(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pending.isNotEmpty()) {
            item { Text("需要你确认归属（${pending.size}）", style = MaterialTheme.typography.titleMedium) }
            items(pending, key = { it.first.path }) { (candidate, sessions) ->
                ConfirmCard(candidate, sessions) { session ->
                    scope.launch {
                        ServiceLocator.recordingRepository.enqueue(
                            caseId = session.caseId,
                            callId = session.callId,
                            candidate = candidate,
                            durationSec = null,
                            phone = session.number,
                            source = RecordingWatchService.SOURCE_APP_AUTO,
                        )
                        PendingConfirmations.remove(candidate.path)
                        UploadScheduler.enqueueNow(context)
                    }
                }
            }
            item { HorizontalDivider() }
        }

        item { Text("上传队列", style = MaterialTheme.typography.titleMedium) }

        if (items.isEmpty()) {
            item {
                Text(
                    "队列为空。通话结束后，系统录下的录音会自动出现在这里。",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        items(items, key = { it.fileHash }) { item ->
            QueueRow(
                item = item,
                onRetry = {
                    scope.launch {
                        ServiceLocator.recordingRepository.retryNow(item.fileHash)
                        UploadScheduler.enqueueNow(context)
                    }
                },
                onDrop = { scope.launch { ServiceLocator.recordingRepository.drop(item.fileHash) } },
            )
        }
    }
}

@Composable
private fun ConfirmCard(
    candidate: RecordingCandidate,
    sessions: List<CallSession>,
    onPick: (CallSession) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(candidate.fileName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "录制于 ${fmt(candidate.lastModified)} · ${candidate.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text("这段录音属于哪一通？", style = MaterialTheme.typography.bodyMedium)
            sessions.forEach { s ->
                OutlinedButton(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${s.number} · ${fmt(s.dialStartTs)}")
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: UploadItemEntity, onRetry: () -> Unit, onDrop: () -> Unit) {
    val status = runCatching { UploadStatus.valueOf(item.status) }.getOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.fileName, style = MaterialTheme.typography.bodyLarge)
                Text(statusLabel(status), style = MaterialTheme.typography.bodySmall, color = statusColor(status))
            }
            Text(
                "案件 ${item.caseId} · ${item.sizeBytes / 1024} KB · ${fmt(item.createdTs)}" +
                    (item.durationSec?.let { " · 通话 ${it}s" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            item.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (status == UploadStatus.FAILED || status == UploadStatus.RETRYING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRetry) { Text("立即重试") }
                    TextButton(onClick = onDrop) { Text("放弃这条") }
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: UploadStatus?) = when (s) {
    UploadStatus.UPLOADED -> MaterialTheme.colorScheme.primary
    UploadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun statusLabel(s: UploadStatus?) = when (s) {
    UploadStatus.PENDING -> "待上传"
    UploadStatus.UPLOADING -> "上传中"
    UploadStatus.RETRYING -> "等待重试"
    UploadStatus.FAILED -> "失败"
    UploadStatus.UPLOADED -> "已上传"
    UploadStatus.NEEDS_CONFIRMATION -> "待确认"
    null -> "-"
}

private fun fmt(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
