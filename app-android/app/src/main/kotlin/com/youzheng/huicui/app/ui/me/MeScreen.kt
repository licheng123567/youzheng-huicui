package com.youzheng.huicui.app.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.BuildConfig
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.ui.login.roleLabel
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MeScreen(modifier: Modifier = Modifier, me: StateFlow<Me?>, onLogout: () -> Unit) {
    val profile by me.collectAsState()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (profile == null) {
            Text("未获取到主体信息（GET /me 失败）")
        } else {
            val p = profile!!
            Text(p.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Text("${p.role?.value?.let(::roleLabel) ?: "未知角色"} · ${p.org?.name.orEmpty()}")

            Text("权限（${p.permissions?.size ?: 0} 项）", style = MaterialTheme.typography.titleSmall)
            p.permissions.orEmpty().forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            "通话录音自动上传：未实现（M-A2）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        TextButton(onClick = onLogout) { Text("退出登录") }
    }
}
