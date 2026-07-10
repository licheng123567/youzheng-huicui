package com.youzheng.huicui.app.ui.changepwd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.data.auth.PasswordRepository
import kotlinx.coroutines.launch

/**
 * 首登强制改密（B-04方案A）。没有「跳过」按钮 —— 后端 JwtAuthFilter 也不会放行其它端点。
 */
@Composable
fun ChangePasswordScreen(repo: PasswordRepository, onDone: () -> Unit) {
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("首次登录，请修改口令", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = oldPwd, onValueChange = { oldPwd = it },
            label = { Text("当前口令") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newPwd, onValueChange = { newPwd = it },
            label = { Text("新口令") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it },
            label = { Text("确认新口令") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (newPwd != confirm) { error = "两次输入的新口令不一致"; return@Button }
                if (newPwd.length < 8) { error = "新口令至少 8 位"; return@Button }
                busy = true; error = null
                scope.launch {
                    val err = repo.changeOwnPassword(oldPwd, newPwd)
                    busy = false
                    if (err == null) onDone() else error = err
                }
            },
        ) { Text("提交") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
