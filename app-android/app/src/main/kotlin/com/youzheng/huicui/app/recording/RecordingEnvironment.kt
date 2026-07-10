package com.youzheng.huicui.app.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 机型自检（BR-APP-08 / PRD §3.6）。把「这台手机能不能自动回传录音」拆成可展示、可引导的若干项。
 *
 * 关键的诚实之处：**自检通过 ≠ 一定能录到音**。真正的证明只有一条 ——
 * 打一通测试电话，看录音文件出没出现（首登引导第 ④ 步）。这里给出的是「必要条件」。
 */
data class EnvCheck(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String,
    /** 该项不满足时是否导致核心功能（自动回传）不可用 */
    val blocking: Boolean,
)

data class RecordingEnv(
    val profile: OemProfile,
    val checks: List<EnvCheck>,
    /** 探测命中的录音目录（绝对路径）；null 表示一个都没命中 */
    val detectedDir: String?,
) {
    val allFilesAccess: Boolean get() = checks.first { it.id == "storage" }.ok
    val blockingFailures: List<EnvCheck> get() = checks.filter { !it.ok && it.blocking }
    val autoUploadAvailable: Boolean get() = blockingFailures.isEmpty() && detectedDir != null
}

object RecordingEnvironment {

    /** 用户手动指定的目录会覆盖探测结果。 */
    var overrideDir: String? = null

    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 11+ 读系统录音目录需要「所有文件访问」。
     * SAF 目录授权虽然更合规，但 **SAF 的 DocumentFile 无法挂 FileObserver** ——
     * 而 FileObserver 是「通话一挂断就拿到录音」的唯一途径。PRD §4.2 已决定走侧载 + 全盘读。
     */
    fun hasAllFilesAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }

    /** 跳到本机「电话」应用的设置页（能跳则跳；跳不了就只能靠图文指引）。 */
    fun dialerSettingsIntent(): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            // 各家包名不同，先试通用的拨号器设置；失败由调用方兜底到系统设置首页
            setClassName("com.android.dialer", "com.android.dialer.app.settings.DialerSettingsActivity")
        }

    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /**
     * 按预设候选目录探测「已经存在录音文件」的目录。
     * 判据是**目录存在且里面有可识别的音频文件** —— 空目录不算命中，
     * 否则一个恰好同名却从没录过音的目录会把自检骗过去。
     */
    fun detectRecordingDir(manufacturer: String? = Build.MANUFACTURER): String? {
        overrideDir?.let { if (File(it).isDirectory) return it }
        val root = externalRoot()
        for (rel in RecordingDirectories.candidatesFor(manufacturer)) {
            val dir = File(root, rel)
            if (!dir.isDirectory) continue
            val hasAudio = dir.listFiles()?.any { it.isFile && RecordingFileName.isSupportedAudio(it.name) } == true
            if (hasAudio) return dir.absolutePath
        }
        return null
    }

    /** 目录存在但还没有录音文件时，也把它当作「候选可用目录」返回，供首登第④步测试通话落盘。 */
    fun firstExistingDir(manufacturer: String? = Build.MANUFACTURER): String? {
        overrideDir?.let { if (File(it).isDirectory) return it }
        val root = externalRoot()
        return RecordingDirectories.candidatesFor(manufacturer)
            .map { File(root, it) }
            .firstOrNull { it.isDirectory }
            ?.absolutePath
    }

    fun check(context: Context): RecordingEnv {
        val profile = RecordingDirectories.profileFor(Build.MANUFACTURER)
        val storageOk = hasAllFilesAccess(context)
        val detected = if (storageOk) (detectRecordingDir() ?: firstExistingDir()) else null

        val checks = listOf(
            EnvCheck(
                "rom", "机型支持系统通话录音",
                ok = profile.hasSystemCallRecording,
                detail = if (profile.hasSystemCallRecording) {
                    "${profile.displayName}：${profile.settingsHint}"
                } else {
                    "${profile.displayName} 不提供系统通话录音，录音需手动上传"
                },
                blocking = true,
            ),
            EnvCheck(
                "storage", "所有文件访问权限",
                ok = storageOk,
                detail = if (storageOk) "已授予" else "未授予 —— 无法读取系统录音目录",
                blocking = true,
            ),
            EnvCheck(
                "dir", "已定位录音目录",
                ok = detected != null,
                detail = detected ?: "未在候选目录中找到录音文件，请先开启系统通话录音并打一通电话，或手动选择目录",
                blocking = true,
            ),
            EnvCheck(
                "phoneState", "读取通话状态",
                ok = hasPermission(context, Manifest.permission.READ_PHONE_STATE),
                detail = "用于感知通话开始/结束，触发录音检测",
                blocking = true,
            ),
            EnvCheck(
                "callLog", "读取通话记录",
                ok = hasPermission(context, Manifest.permission.READ_CALL_LOG),
                detail = "用于判定是否接通与通话时长；拒绝后接通判定退化为「有无录音文件」",
                blocking = false,
            ),
            EnvCheck(
                "callPhone", "直接拨号",
                ok = hasPermission(context, Manifest.permission.CALL_PHONE),
                detail = "拒绝后降级为跳系统拨号盘，需手动按拨出键",
                blocking = false,
            ),
            EnvCheck(
                "notifications", "通知权限",
                ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
                detail = "上传前台服务需要常驻通知；拒绝后上传只能在 App 前台进行",
                blocking = false,
            ),
        )
        return RecordingEnv(profile, checks, detected)
    }
}
