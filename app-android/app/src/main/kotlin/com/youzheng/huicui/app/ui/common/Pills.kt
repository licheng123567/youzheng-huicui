package com.youzheng.huicui.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ui.theme.huicui
import java.util.Locale

/**
 * 状态徽标。ds-admin 的 tag 样式：语义色 12% 底 + 同色文字，比 M3 的 AssistChip 轻，
 * 一行能并排两三个而不撑爆卡片。
 */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

/** 紧急度 → 颜色。契约枚举是 HIGH/MED/LOW —— 不是 MEDIUM。 */
@Composable
fun urgencyColor(urgency: String): Color = when (urgency) {
    "HIGH" -> huicui.danger
    "MED" -> huicui.warning
    else -> huicui.info
}

fun urgencyLabel(urgency: String): String = when (urgency) {
    "HIGH" -> "紧急"
    "MED" -> "一般"
    "LOW" -> "不急"
    else -> urgency
}

/**
 * 待办类别 → **待办的原因**（用户看的是「为什么这条在催我」，不是 `PROMISE_DUE` 这种码）。
 * 契约 TodoCategoryEnum 全集；CO 见前四种，PC 见 TICKET_RECEIPT。
 */
fun todoReason(category: String): String = when (category) {
    "PROMISE_DUE" -> "承诺还款到期"
    "RELEASE_WARN" -> "超时未跟进，即将自动释放"
    "TICKET_RECEIPT" -> "工单待处理/回执待查看"
    "NEW_ASSIGNED" -> "新分配案件，待首次联系"
    "LEGAL_DELIVERY" -> "法务文书待送达"
    "REPAY_MARK" -> "回款待标记"
    "PAYLINK_SEND" -> "缴费链接待发送"
    "REDUCE_APPROVE" -> "减免待审批"
    "T2_RETURN_WARN" -> "即将退回平台公海"
    "T1_DISPATCH_WARN" -> "待派单超时"
    else -> category
}

/**
 * 分 → 万元。工作台「本月回款」后端给的是 **amount_cents（分）**，
 * 此前界面直接把分当数字打出来，于是 12,345.67 元显示成「1234567」。
 *
 * 显式钉 Locale.CHINA：跟随系统 Locale 的话，某些地区（如德语）小数点会变成逗号，
 * 「1,23 万」会被读成一万两千三。金额不能听凭 Locale 摆布。
 */
fun formatWan(cents: Int): String = String.format(Locale.CHINA, "%.2f", cents / 1_000_000.0)
