package com.youzheng.huicui.app.data.auth

/**
 * 令牌存储。抽成接口，好让登录/拦截器逻辑在纯 JVM 单测里跑（Android 的加密 SP 需要真机/Robolectric）。
 */
interface TokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

/** 单测与预览用。 */
class InMemoryTokenStore(initial: String? = null) : TokenStore {
    @Volatile
    private var token: String? = initial

    override fun read(): String? = token
    override fun write(token: String) { this.token = token }
    override fun clear() { token = null }
}
