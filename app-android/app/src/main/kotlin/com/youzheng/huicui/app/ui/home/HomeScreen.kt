package com.youzheng.huicui.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.ui.login.roleLabel

/**
 * M-A1 到此为止：登录通了、`GET /me` 的 permissions[] 拿到了，就把它摊开给人看。
 * 工作台/案件/公海/消息四屏是 PR-A2 的活，这里不放假界面冒充已完成。
 */
@Composable
fun HomeScreen(me: Me?, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("登录成功", style = MaterialTheme.typography.headlineSmall)

        if (me == null) {
            Text("已拿到令牌，但 GET /me 未返回（检查后端可达性）")
        } else {
            Text("${me.name.orEmpty()} · ${me.role?.value?.let(::roleLabel) ?: "未知角色"}")
            Text(me.org?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
            Text("权限（${me.permissions?.size ?: 0} 项）", style = MaterialTheme.typography.titleSmall)
            me.permissions.orEmpty().forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }

        Text(
            "工作台 / 案件 / 公海 / 消息 —— PR-A2",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        TextButton(onClick = onLogout) { Text("退出登录") }
    }
}
