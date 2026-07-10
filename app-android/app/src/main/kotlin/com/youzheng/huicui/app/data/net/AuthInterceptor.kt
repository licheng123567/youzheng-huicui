package com.youzheng.huicui.app.data.net

import com.youzheng.huicui.app.data.auth.TokenStore
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/** 会话级事件：拦截器发现令牌失效/须改密时通知上层导航。 */
interface SessionListener {
    fun onUnauthorized()
    fun onMustChangePassword()
}

/**
 * 统一注入 Bearer；统一处理两类全局响应：
 *   401                              → 清 token，跳登录
 *   403 且 code=MUST_CHANGE_PASSWORD → 跳强制改密（后端 JwtAuthFilter 只放行 GET /me 与 POST /me/password）
 *
 * Idempotency-Key：契约给写操作（上传录音/支付/派单）声明了该头。这里只做**兜底注入**——
 * 作为 application interceptor，intercept() 每个 Call 只跑一次，故该 key 在 OkHttp 自身的
 * 连接级重试中保持不变。但**业务层重发是一个新 Call、会拿到新 key，防不住双扣**：
 * 那种场景（M-A2 录音上传失败后重传）必须由调用方显式传入同一个 key，此处的 `header(...)`
 * 只在调用方没给时才补。
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
    private val listener: SessionListener,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // /auth/* 是公开端点，不带令牌（带了也无害，但没必要把令牌发给登录接口）
        val isAuthEndpoint = original.url.encodedPath.contains("/auth/")
        if (!isAuthEndpoint) {
            tokenStore.read()?.let { builder.header("Authorization", "Bearer $it") }
        }

        if (!isAuthEndpoint &&
            original.method in MUTATING_METHODS &&
            original.header(IDEMPOTENCY_HEADER) == null
        ) {
            builder.header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
        }

        val response = chain.proceed(builder.build())

        when (response.code) {
            401 -> if (!isAuthEndpoint) {
                tokenStore.clear()
                listener.onUnauthorized()
            }
            403 -> {
                // peekBody 不消费响应体，调用方仍可正常读取
                val code = parseApiError(response.peekBody(PEEK_LIMIT).string())?.code
                if (code == "MUST_CHANGE_PASSWORD") listener.onMustChangePassword()
            }
        }
        return response
    }

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val PEEK_LIMIT = 4096L
        val MUTATING_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
