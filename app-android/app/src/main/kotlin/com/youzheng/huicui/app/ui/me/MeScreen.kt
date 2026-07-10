package com.youzheng.huicui.app.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.BuildConfig
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.recording.RecordingEnvironment
import com.youzheng.huicui.app.ui.login.roleLabel
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MeScreen(modifier: Modifier = Modifier, me: StateFlow<Me?>, onLogout: () -> Unit) {
    val profile by me.collectAsState()
    val context = LocalContext.current
    val pending by ServiceLocator.recordingRepository.observePendingCount().collectAsState(initial = 0)
    var confirmLogout by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(ServiceLocator.settings.uploadOnWifiOnly) }
    val env = remember { RecordingEnvironment.check(context) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (profile == null) {
            Text("未获取到主体信息（GET /me 失败）")
        } else {
            val p = profile!!
            Text(p.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Text("${p.role?.value?.let(::roleLabel) ?: "未知角色"} · ${p.org?.name.orEmpty()}")

            Text("权限（${p.permissions?.size ?: 0} 项）", style = MaterialTheme.typography.titleSmall)
            p.permissions.orEmpty().forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("录音回传", style = MaterialTheme.typography.titleSmall)
        Text(
            if (env.autoUploadAvailable) "自动回传已就绪（${env.detectedDir}）"
            else "自动回传不可用：" + env.blockingFailures.joinToString("；") { it.title },
            style = MaterialTheme.typography.bodySmall,
            color = if (env.autoUploadAvailable) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = wifiOnly,
                onCheckedChange = { wifiOnly = it; ServiceLocator.settings.uploadOnWifiOnly = it },
            )
            Text("  仅 WiFi 上传录音（默认关：催收时效优先）")
        }

        Text(
            "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        TextButton(onClick = { if (pending > 0) confirmLogout = true else onLogout() }) {
            Text("退出登录")
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
