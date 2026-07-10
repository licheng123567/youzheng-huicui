package com.youzheng.huicui.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.recording.EnvCheck
import com.youzheng.huicui.app.recording.RecordingEnv
import com.youzheng.huicui.app.recording.RecordingEnvironment
import com.youzheng.huicui.app.recording.SafPaths

/**
 * 首登引导（BR-M4-01c / PRD §3.5）。四步：权限 → 开系统自动录音 → 确认目录 → 测试通话。
 *
 * 可跳过，但工作台会一直挂角标 —— 跳过的代价是「打完电话没有录音」，
 * 那时用户会更迷惑。所以每一步都写清楚「不给会怎样」，而不是只说「请授权」。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    var env by remember { mutableStateOf(RecordingEnvironment.check(context)) }
    fun refresh() { env = RecordingEnvironment.check(context) }

    val runtimePerms = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }.toTypedArray()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }

    var dirError by remember { mutableStateOf<String?>(null) }
    // 用系统目录选择器挑目录（体验好、路径可见），但把 tree URI 还原成绝对路径来用：
    // SAF 的 DocumentFile 挂不了 FileObserver，拿不到「一挂断就有文件」的实时信号。
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafPaths.toFilePath(uri.toString())
        if (path == null) {
            dirError = "请选择内置存储里的目录（不支持 SD 卡）"
        } else {
            dirError = null
            ServiceLocator.settings.recordingDirOverride = path
        }
        refresh()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("开启通话录音自动回传", style = MaterialTheme.typography.headlineSmall)
        Text(
            "有证慧催不会替你拨号，也不会在通话中录音。它读取的是**你手机系统自己录下的通话录音**，" +
                "在通话结束后把它自动传回平台做转写与质检。以下四步只需设置一次。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "机型：${env.profile.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        HorizontalDivider()

        // ① 权限
        StepCard(
            index = 1,
            title = "授予必要权限",
            done = env.checks.filter { it.id in setOf("phoneState", "callLog", "callPhone", "notifications") }
                .all { it.ok },
        ) {
            env.checks.filter { it.id in setOf("phoneState", "callLog", "callPhone", "notifications") }
                .forEach { CheckRow(it) }
            Button(onClick = { permLauncher.launch(runtimePerms) }, modifier = Modifier.fillMaxWidth()) {
                Text("逐项授权")
            }
        }

        // ② 系统自动通话录音
        StepCard(
            index = 2,
            title = "开启系统「通话自动录音」",
            done = false,   // 无法从外部读取该开关状态，只能靠第 ④ 步的测试通话证明
        ) {
            Text(env.profile.settingsHint, style = MaterialTheme.typography.bodyMedium)
            if (!env.profile.hasSystemCallRecording) {
                Text(
                    "本机型（${env.profile.displayName}）不提供系统通话录音，自动回传无法工作。" +
                        "请改用国内主流品牌手机，或在网页端手动上传录音。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "这个开关在系统「电话」应用里，App 无法代你打开，也无法读取它的状态 —— " +
                    "唯一能证明它开着的办法是第 ④ 步打一通测试电话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedButton(
                onClick = {
                    runCatching { settingsLauncher.launch(RecordingEnvironment.dialerSettingsIntent()) }
                        .onFailure {
                            settingsLauncher.launch(Intent(android.provider.Settings.ACTION_SETTINGS))
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开系统设置") }
        }

        // ③ 录音目录
        StepCard(index = 3, title = "定位录音目录", done = env.detectedDir != null) {
            env.checks.filter { it.id in setOf("storage", "dir") }.forEach { CheckRow(it) }
            if (!env.allFilesAccess) {
                Text(
                    "Android 11 起，读取系统录音目录需要「所有文件访问」权限。" +
                        "本 App 只读取录音目录里与你的通话时间窗匹配的音频文件，不扫描其它内容。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { settingsLauncher.launch(RecordingEnvironment.allFilesAccessIntent(context)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("授予所有文件访问") }
            } else if (env.detectedDir == null) {
                Text(
                    "没在候选目录里找到录音文件。先按第 ② 步开启系统通话录音、随便打一通电话，再回来点「重新检测」。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Text("重新检测") }
                    OutlinedButton(onClick = { dirPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Text("手动选目录")
                    }
                }
                dirError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        // ④ 测试通话
        StepCard(index = 4, title = "打一通测试电话", done = false) {
            Text(
                "拨给你自己的另一个号码，聊 10 秒后挂断。App 会在通话结束后自动检测录音、" +
                    "匹配案件并上传。回到「上传队列」页就能看到它。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "这一步是唯一能真正证明整条链路通了的检验 —— 前三步全绿也不代表你的 ROM 真的录到了音。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        HorizontalDivider()

        Button(
            onClick = { ServiceLocator.settings.onboardingDone = true; onDone() },
            enabled = env.autoUploadAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (env.autoUploadAvailable) "完成，开始作业" else "请先完成上面的必要项") }

        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("暂时跳过（录音将无法自动回传）")
        }
    }
}

@Composable
private fun StepCard(index: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${if (done) "✓" else "$index."} $title",
                style = MaterialTheme.typography.titleMedium,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun CheckRow(c: EnvCheck) {
    Column {
        Text(
            "${if (c.ok) "✓" else "○"} ${c.title}${if (!c.ok && c.blocking) "（必需）" else ""}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (c.ok) MaterialTheme.colorScheme.primary
            else if (c.blocking) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** 供工作台角标使用。 */
fun needsOnboarding(env: RecordingEnv): Boolean = !env.autoUploadAvailable
