package com.youzheng.huicui.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youzheng.huicui.app.data.session.AppRoles
import com.youzheng.huicui.app.ui.changepwd.ChangePasswordScreen
import com.youzheng.huicui.app.ui.gate.UnsupportedRoleScreen
import com.youzheng.huicui.app.ui.login.ChooseAccountScreen
import com.youzheng.huicui.app.ui.login.LoginScreen
import com.youzheng.huicui.app.ui.login.LoginStep
import com.youzheng.huicui.app.ui.login.LoginViewModel
import com.youzheng.huicui.app.ui.main.MainScreen
import com.youzheng.huicui.app.ui.onboarding.OnboardingScreen
import com.youzheng.huicui.app.ui.theme.HuicuiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HuicuiTheme { AppRoot() } }
    }
}

@Suppress("UNCHECKED_CAST")
private object LoginVmFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LoginViewModel(ServiceLocator.authRepository, ServiceLocator.settings) as T
}

@Composable
private fun AppRoot() {
    val vm: LoginViewModel = viewModel(factory = LoginVmFactory)
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    var forcedChangePwd by remember { mutableStateOf(false) }
    var onboardingDone by remember { mutableStateOf(ServiceLocator.settings.onboardingDone) }

    fun logout() {
        scope.launch {
            ServiceLocator.logout()   // 清令牌 + 清 Session + 清案件缓存
            vm.backToInput()
        }
    }

    // 拦截器发现 401 / MUST_CHANGE_PASSWORD 时，把界面拽回正确的地方
    LaunchedEffect(Unit) {
        ServiceLocator.sessionEvents.collect { ev ->
            when (ev) {
                SessionEvent.Unauthorized -> logout()
                SessionEvent.MustChangePassword -> forcedChangePwd = true
            }
        }
    }

    val step = state.step

    // 登录成功后把 Me 放进 Session：全 App 的权限门控都读它，不读角色名
    LaunchedEffect(step) {
        if (step is LoginStep.Done) ServiceLocator.session.set(step.me)
    }

    when {
        // 冷启动用已存 token 恢复会话中：显示 loading，别闪一下登录页再跳主页。
        step is LoginStep.Restoring ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        // 恢复失败但**不是**令牌失效（没网/后端挂了）：令牌还在，别把人赶去登录页——他没网登不回来。
        step is LoginStep.RestoreFailed ->
            RestoreFailedScreen(
                message = step.message,
                onRetry = vm::restore,
                onLogout = ::logout,
            )

        forcedChangePwd || step is LoginStep.MustChangePassword ->
            ChangePasswordScreen(ServiceLocator.passwordRepository) {
                forcedChangePwd = false
                vm.onPasswordChanged()   // 销掉「欠改密」那一笔，否则下次冷启动还会被拽回来
                // 改密后令牌里的 must_change_password 已清，重新登录一次最省心
                logout()
            }

        step is LoginStep.ChooseAccount ->
            ChooseAccountScreen(
                accounts = step.accounts,
                loading = state.loading,
                onPick = { accountId -> vm.chooseAccount(step.loginTicket, accountId) },
                onBack = vm::backToInput,
            )

        // 入口门禁（BR-APP-01）必须排在首登引导前面：
        // 被拦下的管理角色不该先被拉着走一遍「开系统通话录音」的四步引导，那对他们毫无意义。
        step is LoginStep.Done && !AppRoles.canEnter(step.me?.role?.value) ->
            UnsupportedRoleScreen(me = step.me, onLogout = ::logout)

        // 首登引导：录音自动回传是这个 App 的立身之本，进主界面前先把它设好。
        // 可跳过 —— 跳过后作业功能照常，但录音不会自动回传。
        step is LoginStep.Done && !onboardingDone ->
            OnboardingScreen(
                onDone = { onboardingDone = true },
                // 跳过也要**落盘**：只改内存标志的话，每次冷启动都会把整套四步引导再糊用户一脸
                // （真机实测确认）。PRD 允许跳过——代价是工作台持续角标提醒，不是每次重开都重来一遍。
                onSkip = { ServiceLocator.settings.onboardingDone = true; onboardingDone = true },
            )

        step is LoginStep.Done -> MainScreen(onLogout = ::logout)

        else -> LoginScreen(state, vm)
    }
}

/**
 * 恢复会话失败（网络/后端），令牌**未清**。给重试，也给一条主动退出的路 ——
 * 但退出是用户的选择，不是我们替他做的决定。
 */
@Composable
private fun RestoreFailedScreen(message: String, onRetry: () -> Unit, onLogout: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("无法连接服务器", style = MaterialTheme.typography.titleMedium)
            Text(
                "$message\n\n登录状态仍然保留，联网后点重试即可继续，不用重新输入账号密码。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onLogout) { Text("退出登录") }
        }
    }
}
