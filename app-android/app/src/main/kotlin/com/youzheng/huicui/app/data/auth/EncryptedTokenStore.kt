package com.youzheng.huicui.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * JWT 落盘：EncryptedSharedPreferences（AES256-GCM，密钥进 Android Keystore）。
 * 明文 SharedPreferences 在 root 设备上可直接读走 —— 催收员手机上放的是可调 API 的主体令牌，不能裸存。
 */
class EncryptedTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): String? = prefs.getString(KEY_TOKEN, null)

    override fun write(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val PREFS_NAME = "huicui_secure_prefs"
        const val KEY_TOKEN = "jwt"
    }
}
