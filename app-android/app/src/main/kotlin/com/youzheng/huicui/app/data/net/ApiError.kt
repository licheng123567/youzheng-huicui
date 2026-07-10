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
