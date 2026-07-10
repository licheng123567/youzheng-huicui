package com.youzheng.huicui.app.ui.dial

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 拨号跳转：`ACTION_DIAL` 只是把号码填进系统拨号盘，**不需要 CALL_PHONE 权限**，也不会自动拨出。
 * 用 `ACTION_CALL` 直接拨才需要危险权限，且会让「是不是自动打电话骚扰」变成合规问题——不用。
 *
 * 这是 M-A2 录音链路的第一环。**录音检测与自动上传尚未实现**：
 * 目前只是跳转到拨号盘，通话结束后不会有任何录音被采集。
 */
fun dial(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // 平板 / 无电话模块的设备上没有拨号盘
        Toast.makeText(context, "本设备没有拨号功能", Toast.LENGTH_SHORT).show()
    }
}
