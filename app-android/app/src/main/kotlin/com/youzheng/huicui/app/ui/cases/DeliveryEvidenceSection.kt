package com.youzheng.huicui.app.ui.cases

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.data.delivery.DeliverySubmitter
import com.youzheng.huicui.app.data.delivery.DeliveryType
import kotlinx.coroutines.launch
import java.io.File

/**
 * 上门送达拍照存证（PR-2 · PRD 二期的 PC 移动场景）。
 *
 * 两个角色都会看到这块，但**能做的事不一样**，UI 直接把差异画出来而不是等 403：
 *   · 上传 + 记跟进：`case.follow` —— 催收员、物业协调员都有；
 *   · 「同时上链存证」勾选框：`evidence.create` —— 只有物业协调员有，催收员根本看不到这个勾。
 *
 * 照片上传成功后本地即删（cacheDir 里的压缩副本），不做离线队列：
 * 录音必须排队是因为错过就没了；送达照片失败了当场重拍就是。
 */
@Composable
fun DeliveryEvidenceSection(caseId: String, canEvidence: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    var deliveryType by rememberSaveable { mutableStateOf(DeliveryType.COLLECTION_NOTICE.wire) }
    var note by rememberSaveable { mutableStateOf("") }
    var withEvidence by rememberSaveable { mutableStateOf(canEvidence) }   // 协调员默认勾上——来都来了
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 相机往 FileProvider 的 Uri 里写原片；成功后压缩进列表，原片即删
    var pendingShot by remember { mutableStateOf<Pair<Uri, File>?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val (uri, raw) = pendingShot ?: return@rememberLauncherForActivityResult
        pendingShot = null
        if (!ok) { raw.delete(); return@rememberLauncherForActivityResult }
        scope.launch {
            runCatching { ServiceLocator.photoCompressor.prepare(uri) }
                .onSuccess { photos = photos + it }
                .onFailure { message = it.message ?: "照片处理失败" }
            raw.delete()
        }
    }
    val pickGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { uri ->
                runCatching { ServiceLocator.photoCompressor.prepare(uri) }
                    .onSuccess { photos = photos + it }
                    .onFailure { message = it.message ?: "照片处理失败" }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("送达存证", style = MaterialTheme.typography.titleMedium)
        Text(
            "上门送达文书后拍照留痕：照片挂到案件、记入跟进时间线" +
                if (canEvidence) "，可同时发起上链存证。" else "。发起存证需要物业协调员在网页端操作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeliveryType.entries.forEach { t ->
                FilterChip(
                    selected = deliveryType == t.wire,
                    onClick = { deliveryType = t.wire },
                    label = { Text(t.label) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                val dir = File(context.cacheDir, "delivery").apply { mkdirs() }
                val raw = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", raw)
                pendingShot = uri to raw
                takePicture.launch(uri)
            }) { Text("拍照") }
            OutlinedButton(enabled = !busy, onClick = {
                pickGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("相册") }
        }

        photos.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "照片 ${i + 1} · ${f.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(enabled = !busy, onClick = { f.delete(); photos = photos - f }) { Text("移除") }
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（选填，如：门贴/交予家属）") },
            minLines = 1,
        )

        if (canEvidence) {
            Row(Modifier.padding(top = 0.dp)) {
                Checkbox(checked = withEvidence, onCheckedChange = { withEvidence = it }, enabled = !busy)
                Text(
                    "同时上链存证（按次计费，进存证清单）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && photos.isNotEmpty(),
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val r = ServiceLocator.deliverySubmitter.submit(
                            caseId = caseId,
                            photos = photos,
                            deliveryType = DeliveryType.entries.first { it.wire == deliveryType },
                            note = note,
                            withEvidence = canEvidence && withEvidence,
                        )
                        message = when (r) {
                            is DeliverySubmitter.Result.Success ->
                                if (r.evidenced) "已上传 ${r.attachmentIds.size} 张并记跟进，存证已发起"
                                else "已上传 ${r.attachmentIds.size} 张并记跟进"
                            is DeliverySubmitter.Result.EvidenceFailed ->
                                "照片与跟进已成，但存证失败（${r.message}），稍后可在网页端补发起"
                            is DeliverySubmitter.Result.Failed ->
                                if (r.stage == DeliverySubmitter.Stage.UPLOAD) "上传失败：${r.message}"
                                else "照片已上传，但记跟进失败（${r.message}），请重试"
                        }
                        // 只有走完（或仅差存证一步）才清空；任一硬失败都保留照片让用户原样重试
                        if (r !is DeliverySubmitter.Result.Failed) {
                            photos.forEach { it.delete() }
                            photos = emptyList()
                            note = ""
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "提交中…" else "上传并记跟进") }
            if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp))
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
