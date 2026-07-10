package com.youzheng.huicui.app.ui.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.api.models.Me
import com.youzheng.huicui.app.data.session.AppRoles

/**
 * 入口门禁被拦下时的落地页（BR-APP-01）。
 *
 * 这一屏最重要的是**说清楚为什么**，而不是甩一句「无权访问」。
 * 被拦下的人多半是物业负责人或服务商负责人，他们会以为是 bug。
 *
 * 「切换账号」不是摆设：一号多账号（BR-M1-11）下同一个手机号可能同时挂着
 * 物业协调员与催收员两个账号（种子里的 `13900009000` 就是），退出后重新登录选另一个即可进入。
 */
@Composable
fun UnsupportedRoleScreen(me: Me?, onLogout: () -> Unit) {
    val role = me?.role?.value

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("请使用网页端", style = MaterialTheme.typography.headlineSmall)

        Text(
            "你的角色是「${AppRoles.label(role)}」。" +
                "这个 App 只做外勤作业 —— 催收员的拨号与录音回传、物业协调员的上门送达存证。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("你的日常工作在网页端", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (role) {
                        "PL" -> "项目档案、佣金提案、减免核准、成员管理、经营报表 —— 都是桌面密度的作业。" +
                            "案件通话由你的协调员与催收员在 App 上完成，录音会自动回传，你在网页端回听。"
                        "VL" -> "接单、分单、退案、佣金结算、成员管理。你不直接给业主打电话（也没有这个权限），" +
                            "案件作业归催收员。"
                        "SA", "SE" -> "派单、参数配置、质检复核、结算与报表。"
                        else -> "案件作业之外的管理与配置工作都在网页端。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("同一个手机号下有多个账号？", style = MaterialTheme.typography.titleSmall)
                Text(
                    "退出后用短信验证码登录，会让你在该手机号名下的账号里选一个。" +
                        "选中催收员或物业协调员账号即可进入作业界面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("退出登录 / 切换账号")
        }
    }
}
