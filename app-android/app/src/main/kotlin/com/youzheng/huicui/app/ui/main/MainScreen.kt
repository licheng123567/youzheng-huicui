package com.youzheng.huicui.app.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.ui.cases.CaseDetailScreen
import com.youzheng.huicui.app.ui.cases.CasesScreen
import com.youzheng.huicui.app.ui.me.MeScreen
import com.youzheng.huicui.app.ui.notifications.NotificationsScreen
import com.youzheng.huicui.app.ui.workbench.WorkbenchScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    WORKBENCH("工作台", Icons.Filled.Home),
    CASES("案件", Icons.Filled.List),
    MESSAGES("消息", Icons.Filled.Notifications),
    ME("我的", Icons.Filled.AccountCircle),
}

/**
 * 底部四 Tab。案件详情不是第五个 Tab，而是压在当前 Tab 之上的一层——
 * 从工作台待办点进案件，再返回时要回到工作台，而不是跳到案件 Tab。
 */
@Composable
fun MainScreen(onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.WORKBENCH) }
    var openCaseId by remember { mutableStateOf<String?>(null) }
    var unread by remember { mutableStateOf(0) }

    val caseId = openCaseId
    if (caseId != null) {
        CaseDetailScreen(caseId = caseId, onBack = { openCaseId = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            if (t == Tab.MESSAGES && unread > 0) {
                                BadgedBox(badge = { Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                                    Icon(t.icon, contentDescription = t.label)
                                }
                            } else {
                                Icon(t.icon, contentDescription = t.label)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (tab) {
            Tab.WORKBENCH -> WorkbenchScreen(inner) { id -> openCaseId = id }
            Tab.CASES -> CasesScreen(inner) { id -> openCaseId = id }
            Tab.MESSAGES -> NotificationsScreen(inner) { unread = it }
            Tab.ME -> MeScreen(inner, me = ServiceLocator.session.me, onLogout = onLogout)
        }
    }
}
