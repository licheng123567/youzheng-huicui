package com.youzheng.huicui.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.youzheng.huicui.app.ServiceLocator

/**
 * 「这条记录到底是谁」——录音队列项、工作台待办都只带一个裸 caseId，
 * 而催收员认人靠的是「业主 + 小区 + 房号」，不是 `案件 12` 这种内部主键。
 *
 * 取数顺序：本地案件缓存（Room，几乎必中——待办/录音都是自己手上的案件）→ 拿不到才回源 detail()。
 * 反过来先打网络会让离线时整屏空白，而缓存里明明有名字。
 */
data class CaseSummary(
    val ownerName: String,
    val room: String,
    val projectName: String,
) {
    /** 「张三 · 阳光花园 3-1-502」——小区在前、房号在后，跟纸质催收单的读法一致。 */
    val line: String
        get() = listOf(ownerName, projectName, room)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * 进程内记忆：同一个案件常常在一屏里出现多次（一个案件既「承诺到期」又「临近释放」，
 * 录音队列里一个案件也可能排着好几条）。不记一笔的话，每个卡片各自打一次 detail()，
 * 一屏能打出十几个重复请求。
 */
private val memo = java.util.concurrent.ConcurrentHashMap<String, CaseSummary>()

/** 退出登录时清空——这些业主名/房号属于上一个账号。由 ServiceLocator.logout() 调用。 */
fun clearCaseSummaryMemo() = memo.clear()

@Composable
fun rememberCaseSummary(caseId: String): CaseSummary? {
    var summary by remember(caseId) { mutableStateOf(memo[caseId]) }
    LaunchedEffect(caseId) {
        if (summary != null) return@LaunchedEffect
        val repo = ServiceLocator.caseRepository
        val found = repo.cachedCore(caseId)?.let {
            CaseSummary(it.ownerName, it.room, it.projectName)
        } ?: repo.detail(caseId).getOrNull()?.case?.let {
            CaseSummary(it.ownerName.orEmpty(), it.room.orEmpty(), it.projectName.orEmpty())
        }
        if (found != null) {
            memo[caseId] = found
            summary = found
        }
    }
    return summary
}
