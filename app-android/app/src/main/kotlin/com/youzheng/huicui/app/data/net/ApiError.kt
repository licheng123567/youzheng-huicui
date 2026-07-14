package com.youzheng.huicui.app.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 契约统一错误信封（components/schemas/Error）。 */
@Serializable
data class ApiError(
    val code: String? = null,
    val message: String? = null,
    val traceId: String? = null,
)

/**
 * 带 HTTP 状态码的失败。
 *
 * 存在的理由：调用方经常**必须**区分「令牌失效（401）」和「这会儿网不通 / 后端 500」。
 * 把两者都塞进一个无差别的 Result.failure，调用方只能一律按最坏情况处理 ——
 * 冷启动恢复会话就因此把离线用户的有效令牌当成过期令牌删掉了。
 */
class HttpStatusException(val status: Int, message: String? = null) :
    IllegalStateException(message ?: "HTTP $status")

@PublishedApi
internal val lenientJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 解析错误体。后端对非 2xx 一律回 Error 信封；但 /auth/sms-code 的 429 走的是
 * `{sent,message,retryAfterSeconds}` 这种「非信封」形状 —— 两者都有 message，故宽松解析。
 * 解析不出来就返回 null，由调用方给兜底文案，绝不把原始报文抛给用户。
 */
fun parseApiError(body: String?): ApiError? = parseJsonOrNull<ApiError>(body)

/** 把错误体按某个具体形状再解一次（如 /auth/sms-code 的 429 带 retryAfterSeconds）。 */
inline fun <reified T> parseJsonOrNull(body: String?): T? {
    if (body.isNullOrBlank()) return null
    return runCatching { lenientJson.decodeFromString<T>(body) }.getOrNull()
}
