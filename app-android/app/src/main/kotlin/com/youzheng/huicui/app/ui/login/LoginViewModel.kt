package com.youzheng.huicui.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.data.auth.AccountChoice
import com.youzheng.huicui.app.data.auth.AuthRepository
import com.youzheng.huicui.app.data.auth.LoginOutcome
import com.youzheng.huicui.app.data.auth.SmsCodeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginTab { PASSWORD, SMS }

/** 登录流程走到哪一步。三态在此收口。 */
sealed interface LoginStep {
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

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

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
                // token 到手后立刻取 permissions[]，界面按它门控
                val me = auth.me().getOrNull()
                _state.update { it.copy(loading = false, step = LoginStep.Done(me)) }
            }
            is LoginOutcome.MustChangePassword ->
                _state.update { it.copy(loading = false, step = LoginStep.MustChangePassword) }
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
