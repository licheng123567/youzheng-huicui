package com.youzheng.huicui.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youzheng.huicui.app.ui.changepwd.ChangePasswordScreen
import com.youzheng.huicui.app.ui.home.HomeScreen
import com.youzheng.huicui.app.ui.login.ChooseAccountScreen
import com.youzheng.huicui.app.ui.login.LoginScreen
import com.youzheng.huicui.app.ui.login.LoginStep
import com.youzheng.huicui.app.ui.login.LoginViewModel
import com.youzheng.huicui.app.ui.theme.HuicuiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HuicuiTheme { AppRoot() } }
    }
}

@Suppress("UNCHECKED_CAST")
private object LoginVmFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LoginViewModel(ServiceLocator.authRepository) as T
}

@Composable
private fun AppRoot() {
    val vm: LoginViewModel = viewModel(factory = LoginVmFactory)
    val state by vm.state.collectAsState()

    // 拦截器发现 401 / MUST_CHANGE_PASSWORD 时，把界面拽回正确的地方
    var forcedChangePwd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ServiceLocator.sessionEvents.collect { ev ->
            when (ev) {
                SessionEvent.Unauthorized -> vm.backToInput()
                SessionEvent.MustChangePassword -> forcedChangePwd = true
            }
        }
    }

    val step = state.step
    when {
        forcedChangePwd || step is LoginStep.MustChangePassword ->
            ChangePasswordScreen(ServiceLocator.passwordRepository) {
                forcedChangePwd = false
                // 改密后令牌里的 must_change_password 已清，重新登录一次最省心
                ServiceLocator.authRepository.logout()
                vm.backToInput()
            }

        step is LoginStep.ChooseAccount ->
            ChooseAccountScreen(
                accounts = step.accounts,
                loading = state.loading,
                onPick = { accountId -> vm.chooseAccount(step.loginTicket, accountId) },
                onBack = vm::backToInput,
            )

        step is LoginStep.Done ->
            HomeScreen(me = step.me) {
                ServiceLocator.authRepository.logout()
                vm.backToInput()
            }

        else -> LoginScreen(state, vm)
    }
}
