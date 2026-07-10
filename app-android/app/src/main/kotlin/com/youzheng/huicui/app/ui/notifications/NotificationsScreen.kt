package com.youzheng.huicui.app.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.Notification
import com.youzheng.huicui.app.ui.common.LoadState
import com.youzheng.huicui.app.ui.common.LoadStateBox
import kotlinx.coroutines.launch

/** 消息中心。点开即标已读；未读数回传给底部 Tab 画角标。 */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier, onUnreadCount: (Int) -> Unit) {
    var state by remember { mutableStateOf<LoadState<List<Notification>>>(LoadState.Loading) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        state = LoadState.Loading
        state = ServiceLocator.notificationRepository.list().fold(
            onSuccess = { list ->
                onUnreadCount(list.count { !it.read })
                LoadState.Data(list)
            },
            onFailure = { LoadState.Error(it.message ?: "加载失败") },
        )
    }

    LaunchedEffect(Unit) { load() }

    LoadStateBox(state, modifier, onRetry = { scope.launch { load() } }) { items ->
        if (items.isEmpty()) {
            Text(
                "没有消息",
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline,
            )
            return@LoadStateBox
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { n ->
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = !n.read) {
                        scope.launch {
                            // 标已读失败就别骗人，重新拉一次让服务端说了算
                            ServiceLocator.notificationRepository.markRead(n.id)
                            load()
                        }
                    },
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!n.read) Badge()
                            Text(n.title, style = MaterialTheme.typography.bodyLarge)
                        }
                        n.body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            n.createdAt.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}
