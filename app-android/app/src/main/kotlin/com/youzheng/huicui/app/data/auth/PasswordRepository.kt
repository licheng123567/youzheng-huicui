package com.youzheng.huicui.app.data.auth

import com.youzheng.huicui.app.api.apis.OrgMemberApi
import com.youzheng.huicui.app.api.models.ChangeOwnPasswordRequest
import com.youzheng.huicui.app.data.net.parseApiError
import java.io.IOException

/**
 * POST /me/password —— 首登强制改密（B-04方案A）。
 * must_change_password=TRUE 时后端 JwtAuthFilter 只放行 GET /me 与本端点，前端绕不过去。
 */
class PasswordRepository(private val orgMemberApi: OrgMemberApi) {

    /** @return null=成功；非 null=给用户看的失败原因 */
    suspend fun changeOwnPassword(oldPassword: String, newPassword: String): String? = try {
        val res = orgMemberApi.changeOwnPassword(
            ChangeOwnPasswordRequest(oldPassword = oldPassword, newPassword = newPassword),
        )
        if (res.isSuccessful) null
        else parseApiError(res.errorBody()?.string())?.message ?: "改密失败（HTTP ${res.code()}）"
    } catch (e: IOException) {
        "无法连接服务器（${e.javaClass.simpleName}）"
    }
}
