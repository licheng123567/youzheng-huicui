package com.youzheng.huicui.app.ui.dial

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.recording.RecordingEnvironment

/**
 * 拨号。**必须先把通话会话落库，再拨出去**（BR-APP-03）——
 * `ACTION_CALL` 一发，App 立刻退到后台，随时可能被系统杀掉；
 * 会话记录是通话结束后把录音挂回案件的唯一锚点，晚一步就没了。
 *
 * 有 `CALL_PHONE` 权限 → `ACTION_CALL` 直接拨出，催收员少按一次键。
 * 没有 → 降级 `ACTION_DIAL` 跳系统拨号盘（需手按拨出键）。两条路都由系统通话，
 * **平台不主动外呼、不感知拨打时机**（BR-M4-01b），录音由系统自己录。
 */
suspend fun startCall(context: Context, caseId: String, phone: String): String {
    val session = ServiceLocator.recordingRepository.beginCall(caseId, phone)

    val canCallDirectly = RecordingEnvironment.hasPermission(context, Manifest.permission.CALL_PHONE)
    val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
    val intent = Intent(action, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "本设备没有拨号功能", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        // 权限在运行时被撤销：退回拨号盘
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    return session.callId
}
