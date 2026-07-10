package com.youzheng.huicui.app.data.auth

import com.youzheng.huicui.app.api.apis.AuthApi
import com.youzheng.huicui.app.api.models.LoginByPassword
import com.youzheng.huicui.app.api.models.LoginBySms
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.api.models.SelectAccountRequest
import com.youzheng.huicui.app.data.net.AuthEdgeApi
import com.youzheng.huicui.app.data.net.SmsCodeRequest
import retrofit2.Response
import java.io.IOException

/**
 * 鉴权仓储。网络层薄薄一层，判定逻辑全在纯函数 [LoginResponseMapper] 里 —— 那部分有单测。
 * 这里只负责：调用、把 IOException 归一成 NETWORK、成功时落 token。
 */
class AuthRepository(
    private val edge: AuthEdgeApi,
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
) {

    suspend fun loginByPassword(username: String, password: String): LoginOutcome =
        login { edge.loginByPassword(LoginByPassword(mode = "password", username = username, password = password)) }

    suspend fun loginBySms(phone: String, code: String): LoginOutcome =
        login { edge.loginBySms(LoginBySms(mode = "sms", phone = phone, code = code)) }

    suspend fun selectAccount(loginTicket: String, accountId: String): LoginOutcome =
        login { edge.selectAccount(SelectAccountRequest(loginTicket = loginTicket, accountId = accountId)) }

    suspend fun requestSmsCode(phone: String): SmsCodeOutcome = try {
        val res = edge.requestSmsCode(SmsCodeRequest(phone))
        LoginResponseMapper.mapSmsCode(res.code(), res.body(), res.errorBody()?.string())
    } catch (e: IOException) {
        SmsCodeOutcome.Failed(FailureKind.NETWORK, networkMessage(e))
    }

    /** GET /me —— 拿 permissions[]，界面按它门控（CO 固定 8 项权限）。 */
    suspend fun me(): Result<Me> = try {
        val res = authApi.getMe()
        val body = res.body()
        if (res.isSuccessful && body != null) Result.success(body)
        else Result.failure(IllegalStateException("GET /me 失败：HTTP ${res.code()}"))
    } catch (e: IOException) {
        Result.failure(e)
    }

    fun logout() = tokenStore.clear()

    fun currentToken(): String? = tokenStore.read()

    private suspend fun login(call: suspend () -> Response<com.youzheng.huicui.app.api.models.LoginResult>): LoginOutcome =
        try {
            val res = call()
            val outcome = LoginResponseMapper.map(res.code(), res.body(), res.errorBody()?.string())
            when (outcome) {
                is LoginOutcome.Authenticated -> tokenStore.write(outcome.token)
                is LoginOutcome.MustChangePassword -> tokenStore.write(outcome.token)
                else -> Unit
            }
            outcome
        } catch (e: IOException) {
            LoginOutcome.Failed(FailureKind.NETWORK, networkMessage(e))
        }

    private fun networkMessage(e: IOException): String =
        "无法连接服务器（${e.javaClass.simpleName}）。请确认后端已启动、手机与后端在同一网络。"
}
