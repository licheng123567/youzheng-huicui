package com.youzheng.huicui.app.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.ui.cases.CaseDetailScreen
import com.youzheng.huicui.app.ui.cases.CasesScreen
import com.youzheng.huicui.app.ui.me.MeScreen
import com.youzheng.huicui.app.ui.notifications.NotificationsScreen
import com.youzheng.huicui.app.recording.RecordingSweeper
import com.youzheng.huicui.app.ui.upload.UploadQueueScreen
import com.youzheng.huicui.app.ui.workbench.WorkbenchScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    WORKBENCH("工作台", Icons.Filled.Home),
    CASES("案件", Icons.Filled.List),
    UPLOADS("录音", Icons.Filled.Phone),
    MESSAGES("消息", Icons.Filled.Notifications),
    ME("我的", Icons.Filled.AccountCircle),
}

/**
 * 底部五 Tab（工作台/案件/录音/消息/我的）。案件详情不是第六个 Tab，
 * 而是压在当前 Tab 之上的一层——从工作台待办点进案件，再返回时要回到工作台，而不是跳到案件 Tab。
 */
@Composable
fun MainScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.WORKBENCH) }
    var openCaseId by remember { mutableStateOf<String?>(null) }
    var unread by remember { mutableStateOf(0) }
    val pendingUploads by ServiceLocator.recordingRepository.observePendingCount()
        .collectAsState(initial = 0)

    // 兜底补扫（PRD §3.2 兜底层）：FileObserver 会被国产 ROM 杀掉、事件也会丢。
    // 用户打完电话把 App 划掉、过一小时再打开，录音必须还能被捡回来。
    LaunchedEffect(Unit) { RecordingSweeper.sweep(context) }

    val caseId = openCaseId
    if (caseId != null) {
        CaseDetailScreen(caseId = caseId, onBack = { openCaseId = null })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,   // 灰底衬白卡，卡片才有「浮起来」的层次
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.outline,
                            unselectedTextColor = MaterialTheme.colorScheme.outline,
                        ),
                        icon = {
                            val badge = when (t) {
                                Tab.MESSAGES -> unread
                                Tab.UPLOADS -> pendingUploads
                                else -> 0
                            }
                            if (badge > 0) {
                                BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
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
            Tab.UPLOADS -> UploadQueueScreen(inner) { id -> openCaseId = id }
            Tab.MESSAGES -> NotificationsScreen(inner) { unread = it }
            Tab.ME -> MeScreen(inner, me = ServiceLocator.session.me, onLogout = onLogout)
        }
    }
}
