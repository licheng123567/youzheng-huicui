package com.youzheng.huicui.app.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.BuildConfig
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.data.session.PermissionLabels
import com.youzheng.huicui.app.recording.RecordingEnvironment
import com.youzheng.huicui.app.ui.common.Pill
import com.youzheng.huicui.app.ui.login.roleLabel
import com.youzheng.huicui.app.ui.theme.huicui
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeScreen(modifier: Modifier = Modifier, me: StateFlow<Me?>, onLogout: () -> Unit) {
    val profile by me.collectAsState()
    val context = LocalContext.current
    val pending by ServiceLocator.recordingRepository.observePendingCount().collectAsState(initial = 0)
    var confirmLogout by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(ServiceLocator.settings.uploadOnWifiOnly) }
    val env = remember { RecordingEnvironment.check(context) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile == null) {
            Text("未获取到主体信息（GET /me 失败）")
        } else {
            val p = profile!!

            // 头部：姓名 + 角色（中文全称）+ 组织
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.name.orEmpty().take(1).ifBlank { "?" },
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            p.name.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(
                                p.role?.value?.let(::roleLabel) ?: "未知角色",
                                MaterialTheme.colorScheme.primary,
                            )
                            p.org?.name?.takeIf { it.isNotBlank() }?.let { Pill(it, huicui.teal) }
                        }
                    }
                }
            }

            // 权限：**中文名**。此前直接把 case.call 这种裸码打给用户看。
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "我的权限（${p.permissions?.size ?: 0} 项）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        p.permissions.orEmpty().forEach {
                            Pill(PermissionLabels.label(it), MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (p.permissions.orEmpty().isEmpty()) {
                        Text(
                            "后端未下发任何权限点，本机将无法作业。",
                            style = MaterialTheme.typography.bodySmall,
                            color = huicui.danger,
                        )
                    }
                }
            }
        }

        // 录音回传环境
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("录音回传", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        if (env.autoUploadAvailable) "已就绪" else "不可用",
                        if (env.autoUploadAvailable) huicui.success else huicui.danger,
                    )
                    Text(
                        if (env.autoUploadAvailable) env.detectedDir.orEmpty()
                        else env.blockingFailures.joinToString("；") { it.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("仅 WiFi 上传录音", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "默认关：催收时效优先",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { wifiOnly = it; ServiceLocator.settings.uploadOnWifiOnly = it },
                    )
                }
            }
        }

        Text(
            "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        TextButton(onClick = { if (pending > 0) confirmLogout = true else onLogout() }) {
            Text("退出登录", color = huicui.danger)
        }
    }

    // 队列里还有没传上去的录音：退出会连同本地副本一起清掉（它们属于上一个账号的案件）。
    // 这是不可逆的，必须先问。
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("还有 $pending 条录音没上传") },
            text = {
                Text(
                    "退出登录会清空上传队列并删除这些录音的本地副本，它们将无法再传回平台。" +
                        "系统录音目录里的原始文件不受影响。建议先连上网络等队列传完。",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("仍然退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("再等等") } },
        )
    }
}
