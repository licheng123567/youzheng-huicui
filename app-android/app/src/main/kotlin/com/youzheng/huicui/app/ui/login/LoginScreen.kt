package com.youzheng.huicui.app.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.data.auth.AccountChoice

@Composable
fun LoginScreen(state: LoginUiState, vm: LoginViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("有证慧催", style = MaterialTheme.typography.headlineMedium)
        Text("催收员工作台", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(
                selected = state.tab == LoginTab.PASSWORD,
                onClick = { vm.onTab(LoginTab.PASSWORD) },
                text = { Text("口令登录") },
            )
            Tab(
                selected = state.tab == LoginTab.SMS,
                onClick = { vm.onTab(LoginTab.SMS) },
                text = { Text("短信登录") },
            )
        }

        when (state.tab) {
            LoginTab.PASSWORD -> {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = vm::onUsername,
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPassword,
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LoginTab.SMS -> {
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = vm::onPhone,
                    label = { Text("手机号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = vm::onCode,
                        label = { Text("验证码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = vm::requestSmsCode,
                        enabled = !state.loading && state.smsCooldown == 0,
                    ) {
                        Text(if (state.smsCooldown > 0) "${state.smsCooldown}s 后可重发" else "获取验证码")
                    }
                }
            }
        }

        Button(
            onClick = vm::submit,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.loading) CircularProgressIndicator(Modifier.height(18.dp)) else Text("登录")
        }

        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun ChooseAccountScreen(
    accounts: List<AccountChoice>,
    loading: Boolean,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("选择要登录的账号", style = MaterialTheme.typography.headlineSmall)
        Text("该手机号下有多个可用账号（一号多账号）", style = MaterialTheme.typography.bodySmall)

        accounts.forEach { a ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !loading) { onPick(a.accountId) },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.orgName, style = MaterialTheme.typography.titleMedium)
                    Text("${a.name} · ${roleLabel(a.role)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("返回重新登录")
        }
    }
}

/** 角色对外一律显示中文名（与 Web 端一致，不露 PC/CO/PL 裸码）。 */
fun roleLabel(role: String): String = when (role) {
    "SA" -> "平台超管"
    "SE" -> "平台运营"
    "PL" -> "物业负责人"
    "PC" -> "物业协调员"
    "VL" -> "服务商负责人"
    "CO" -> "催收员"
    else -> role
}
