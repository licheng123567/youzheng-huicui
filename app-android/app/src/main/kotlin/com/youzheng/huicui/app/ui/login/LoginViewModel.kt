package com.youzheng.huicui.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.data.auth.AccountChoice
import com.youzheng.huicui.app.data.auth.AuthRepository
import com.youzheng.huicui.app.data.auth.LoginOutcome
import com.youzheng.huicui.app.data.auth.SmsCodeOutcome
import com.youzheng.huicui.app.data.net.HttpStatusException
import com.youzheng.huicui.app.recording.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginTab { PASSWORD, SMS }

/** 登录流程走到哪一步。三态在此收口。 */
sealed interface LoginStep {
    /** 冷启动用已存 token 恢复会话中——显示 loading，别闪登录页。 */
    data object Restoring : LoginStep

    /**
     * 恢复会话时网络不通/后端出错（**不是**令牌失效）。令牌**保留**，给重试。
     * 这一态必须存在：不然只能在「清掉令牌回登录页」和「装作已登录」之间二选一，
     * 而前者会把地下车库里的催收员彻底锁在门外——他没网，根本重新登录不了。
     */
    data class RestoreFailed(val message: String) : LoginStep

    data object Input : LoginStep
    data class ChooseAccount(val loginTicket: String, val accounts: List<AccountChoice>) : LoginStep
    data object MustChangePassword : LoginStep
    data class Done(val me: Me?) : LoginStep
}

data class LoginUiState(
    val tab: LoginTab = LoginTab.PASSWORD,
    val username: String = "",
    val password: String = "",
    val phone: String = "",
    val code: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val smsCooldown: Int = 0,
    val step: LoginStep = LoginStep.Input,
)

class LoginViewModel(
    private val auth: AuthRepository,
    private val settings: AppSettings,
) : ViewModel() {

    // 冷启动自动恢复会话：token 是加密持久化的（EncryptedTokenStore），此前**启动却从不读它**，
    // 于是每次关掉 App 重开都回登录页——这就是「一关闭就要求输入用户密码」的根因。
    private val _state = MutableStateFlow(
        LoginUiState(step = if (auth.currentToken().isNullOrBlank()) LoginStep.Input else LoginStep.Restoring)
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        if (_state.value.step is LoginStep.Restoring) restore()
    }

    /**
     * 用已存令牌恢复会话。三条出路，**必须分清**：
     *
     *   · 令牌欠一次改密 → 直接去改密页。`GET /me` 对这种令牌返回 200（它在白名单里），
     *     光看 /me 是看不出来的，所以登录时就记了一笔 [AppSettings.mustChangePassword]。
     *   · /me 返回 **401** → 令牌真失效了：清掉，回登录页。
     *   · 其它失败（没网、超时、5xx）→ **保留令牌**，进 RestoreFailed 给重试。
     *     曾经这里把所有失败都当成过期并 logout()：催收员在信号盲区冷启动，
     *     有效令牌被删，而他没网又登不回来，本地排队的录音和离线案件缓存一起够不着了。
     */
    fun restore() {
        viewModelScope.launch {
            _state.update { it.copy(step = LoginStep.Restoring) }

            if (settings.mustChangePassword) {
                _state.update { it.copy(step = LoginStep.MustChangePassword) }
                return@launch
            }

            auth.me().fold(
                onSuccess = { me -> _state.update { it.copy(step = LoginStep.Done(me)) } },
                onFailure = { e ->
                    if (e is HttpStatusException && e.status == 401) {
                        auth.logout()
                        _state.update { it.copy(step = LoginStep.Input) }
                    } else {
                        _state.update {
                            it.copy(step = LoginStep.RestoreFailed(e.message ?: "无法连接服务器"))
                        }
                    }
                },
            )
        }
    }

    /** 改密成功后调用：销掉那一笔「欠改密」，否则下次冷启动还会被拽回改密页。 */
    fun onPasswordChanged() {
        settings.mustChangePassword = false
    }

    fun onTab(tab: LoginTab) = _state.update { it.copy(tab = tab, error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v) }
    fun onPassword(v: String) = _state.update { it.copy(password = v) }
    fun onPhone(v: String) = _state.update { it.copy(phone = v) }
    fun onCode(v: String) = _state.update { it.copy(code = v) }
    fun dismissError() = _state.update { it.copy(error = null, notice = null) }

    fun requestSmsCode() {
        val phone = _state.value.phone.trim()
        if (phone.isBlank()) {
            _state.update { it.copy(error = "请先填写手机号") }
            return
        }
        if (_state.value.smsCooldown > 0) return

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notice = null) }
            when (val r = auth.requestSmsCode(phone)) {
                is SmsCodeOutcome.Sent -> {
                    _state.update { it.copy(loading = false, notice = "验证码已发送") }
                    startCooldown(DEFAULT_RESEND_SECONDS)
                }
                is SmsCodeOutcome.Failed -> {
                    _state.update { it.copy(loading = false, error = r.message) }
                    // 429 带 retryAfterSeconds：把后端算好的剩余秒数直接用上，别自己猜
                    r.retryAfterSeconds?.takeIf { it > 0 }?.let { startCooldown(it) }
                }
            }
        }
    }

    fun submit() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notice = null) }
            val outcome = when (s.tab) {
                LoginTab.PASSWORD -> auth.loginByPassword(s.username.trim(), s.password)
                LoginTab.SMS -> auth.loginBySms(s.phone.trim(), s.code.trim())
            }
            handle(outcome)
        }
    }

    fun chooseAccount(loginTicket: String, accountId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            handle(auth.selectAccount(loginTicket, accountId))
        }
    }

    fun backToInput() = _state.update { it.copy(step = LoginStep.Input, error = null) }

    private suspend fun handle(outcome: LoginOutcome) {
        when (outcome) {
            is LoginOutcome.Authenticated -> {
                settings.mustChangePassword = false
                // token 到手后立刻取 permissions[]，界面按它门控
                val me = auth.me().getOrNull()
                _state.update { it.copy(loading = false, step = LoginStep.Done(me)) }
            }
            is LoginOutcome.MustChangePassword -> {
                // 记一笔：令牌已经落盘了，用户此刻杀掉 App 再开，光凭 /me（返 200）认不出他还欠一次改密
                settings.mustChangePassword = true
                _state.update { it.copy(loading = false, step = LoginStep.MustChangePassword) }
            }
            is LoginOutcome.ChoiceRequired ->
                _state.update {
                    it.copy(loading = false, step = LoginStep.ChooseAccount(outcome.loginTicket, outcome.accounts))
                }
            is LoginOutcome.Failed ->
                _state.update { it.copy(loading = false, error = outcome.message) }
        }
    }

    private fun startCooldown(seconds: Int) {
        viewModelScope.launch {
            var left = seconds
            while (left > 0) {
                _state.update { it.copy(smsCooldown = left) }
                delay(1_000)
                left--
            }
            _state.update { it.copy(smsCooldown = 0) }
        }
    }

    private companion object {
        /** 与后端 AuthController.SMS_RESEND_INTERVAL_MS 一致 */
        const val DEFAULT_RESEND_SECONDS = 60
    }
}
