package com.youzheng.huicui.app.ui.cases

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.data.delivery.DeliverySubmitter
import com.youzheng.huicui.app.data.delivery.DeliveryType
import com.youzheng.huicui.app.ui.theme.huicui
import kotlinx.coroutines.launch
import java.io.File

/**
 * 上门送达拍照存证 —— **物业协调员（PC）专属**。
 *
 * 权限归属（这一节此前是错的）：入口从 `case.follow` 收窄到 `evidence.create`。
 * `case.follow` 催收员（CO）也有，于是催收员也看到一整套「律师函 / 诉讼文书」送达类型 ——
 * 可上门送达文书本就是协调员的活（契约里 `LEGAL_DELIVERY` 待办只发给 PC），
 * 催收员选完类型拍完照，末尾那个「上链存证」勾还因为没权限而不显示。
 * 半套 UI 配半套权限，正是「交互逻辑很怪」的来源。现在催收员干脆看不到这一节。
 *
 * 交互按用户要求做成**先选类型、再拍照**的顺序：没选类型时拍照按钮是灰的。
 * 类型不给默认值 —— 默认「催收单」会让人闭着眼把律师函拍成催收单，而这两者的法律含义完全不同。
 *
 * 照片上传成功后本地即删（cacheDir 里的压缩副本），不做离线队列：
 * 录音必须排队是因为错过就没了；送达照片失败了当场重拍就是。
 */
@Composable
fun DeliveryEvidenceSection(caseId: String, isCoordinator: Boolean, canEvidence: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    var deliveryType by rememberSaveable { mutableStateOf<String?>(null) }   // 不给默认：必须显式选
    var note by rememberSaveable { mutableStateOf("") }
    var withEvidence by rememberSaveable { mutableStateOf(true) }            // 来都来了，默认上链
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 催收员没有送达类型可选（送达是协调员的事），他的照片一律记作 OTHER：
    // 硬让他在「律师函/诉讼文书」里挑一个，只会挑出一条假的法律事实。
    val typeChosen = !isCoordinator || deliveryType != null
    val effectiveType = if (isCoordinator) deliveryType else DeliveryType.OTHER.wire

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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (isCoordinator) "送达存证" else "现场拍照留痕",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (isCoordinator) {
                "上门送达文书后拍照留痕：照片挂到案件、记入跟进时间线" +
                    if (canEvidence) "，可同时发起上链存证。" else "。（该案件当前不可发起存证）"
            } else {
                "上门催收拍照留痕：照片挂到案件、记入跟进时间线（上门送达文书与上链存证由物业协调员操作）。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 送达类型只对协调员有意义；催收员不选类型（一律记 OTHER），也就没有这一步
        if (isCoordinator) {
            StepHeader(1, "选送达类型", done = typeChosen)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeliveryType.entries.forEach { t ->
                    FilterChip(
                        selected = deliveryType == t.wire,
                        onClick = { deliveryType = t.wire },
                        enabled = !busy,
                        label = { Text(t.label) },
                    )
                }
            }
        }

        StepHeader(if (isCoordinator) 2 else 1, "拍照留痕", done = photos.isNotEmpty())
        if (!typeChosen) {
            Text(
                "请先选择送达类型 —— 律师函和催收单的法律含义不同，存证时必须标对。",
                style = MaterialTheme.typography.bodySmall,
                color = huicui.warning,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && typeChosen, onClick = {
                val dir = File(context.cacheDir, "delivery").apply { mkdirs() }
                val raw = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", raw)
                pendingShot = uri to raw
                takePicture.launch(uri)
            }) { Text("拍照") }
            OutlinedButton(enabled = !busy && typeChosen, onClick = {
                pickGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("相册") }
        }

        photos.forEachIndexed { i, f ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("照片 ${i + 1} · ${f.length() / 1024} KB", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { f.delete(); photos = photos - f }) { Text("移除") }
            }
        }

        StepHeader(if (isCoordinator) 3 else 2, "提交", done = false)
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("备注（选填，如：门贴/交予家属）") },
            minLines = 1,
        )

        // 上链存证要两个条件同时成立：**有权限**（evidence.create，即协调员）
        // 且**案件状态机允许**（availableActions 含 evidence）。两者是各自独立变的。
        if (isCoordinator && canEvidence) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withEvidence, onCheckedChange = { withEvidence = it }, enabled = !busy)
                Text("同时上链存证（按次计费，进存证清单）", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && typeChosen && photos.isNotEmpty(),
                onClick = {
                    busy = true
                    message = null
                    val chosen = DeliveryType.entries.first { it.wire == effectiveType }
                    scope.launch {
                        val r = ServiceLocator.deliverySubmitter.submit(
                            caseId = caseId,
                            photos = photos,
                            deliveryType = chosen,
                            note = note,
                            withEvidence = isCoordinator && canEvidence && withEvidence,
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
                            deliveryType = null
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "提交中…" else "上传并记跟进") }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp))
        }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** 步骤号徽章。做完的一步打勾变绿——让「先选类型再拍照」这个顺序在界面上看得见。 */
@Composable
private fun StepHeader(step: Int, title: String, done: Boolean) {
    val color = if (done) huicui.success else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(20.dp).clip(MaterialTheme.shapes.large).background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            } else {
                Text("$step", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}
