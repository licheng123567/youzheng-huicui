package com.youzheng.huicui.app.recording

import android.content.Context

/** 少量本地设置。用普通 SharedPreferences：这里没有敏感数据。 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("huicui_settings", Context.MODE_PRIVATE)

    /** PRD §3.4：默认**关**。催收时效优先，录音单文件通常 <10MB。 */
    var uploadOnWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(v) = prefs.edit().putBoolean(KEY_WIFI_ONLY, v).apply()

    /** 用户手动指定的录音目录（自动探测失败时）。 */
    var recordingDirOverride: String?
        get() = prefs.getString(KEY_DIR, null)
        set(v) {
            prefs.edit().putString(KEY_DIR, v).apply()
            RecordingEnvironment.overrideDir = v
        }

    /** 首登引导是否已完成（可跳过，但工作台持续角标提醒）。 */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING, v).apply()

    fun restore() {
        RecordingEnvironment.overrideDir = recordingDirOverride
    }

    private companion object {
        const val KEY_WIFI_ONLY = "upload_wifi_only"
        const val KEY_DIR = "recording_dir"
        const val KEY_ONBOARDING = "onboarding_done"
    }
}
