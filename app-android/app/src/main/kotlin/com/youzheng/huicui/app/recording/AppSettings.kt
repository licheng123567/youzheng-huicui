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

    /**
     * 「这个令牌还欠一次改密」。
     *
     * 后端首登会**连同令牌一起**下发 MUST_CHANGE_PASSWORD，而 `GET /me` 对这种令牌照样返回 200
     * （它在鉴权白名单里），`Me` 里也没有任何标志位。所以冷启动恢复会话时，客户端自己不记一笔，
     * 就会把「还没改密」的用户直接放进主页，然后每个业务请求挨个 403。
     */
    var mustChangePassword: Boolean
        get() = prefs.getBoolean(KEY_MUST_CHANGE_PWD, false)
        set(v) = prefs.edit().putBoolean(KEY_MUST_CHANGE_PWD, v).apply()

    fun restore() {
        RecordingEnvironment.overrideDir = recordingDirOverride
    }

    private companion object {
        const val KEY_WIFI_ONLY = "upload_wifi_only"
        const val KEY_DIR = "recording_dir"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_MUST_CHANGE_PWD = "must_change_password"
    }
}
