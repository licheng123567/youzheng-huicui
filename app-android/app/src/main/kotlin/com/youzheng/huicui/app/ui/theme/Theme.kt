package com.youzheng.huicui.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题对齐 web 端 ds-admin 设计系统（frontend/src/styles/ds-admin.css）——政务蓝品牌。
 * 此前只覆盖了 primary/error/background 三个槽，其余走 Material3 默认（默认 secondary 是紫），
 * 所以界面到处冒紫色、和 web 端对不齐、也不成体系。这里补齐整套色板 + 语义色 + 圆角 + 字重。
 */

// ── ds-admin 令牌（:root）──
private val Primary = Color(0xFF2563EB)      // --primary 政务蓝
private val PrimaryDark = Color(0xFF1D4ED8)  // --primary-d hover
private val PrimaryLight = Color(0xFFECF3FF) // --primary-l 选中底/浅蓝
private val Success = Color(0xFF15A35B)      // --success
private val Warning = Color(0xFFE6A23C)      // --warning
private val Danger = Color(0xFFF56C6C)       // --danger
private val Info = Color(0xFF909399)         // --info
private val Teal = Color(0xFF11A8B5)         // --teal 辅助
private val Purple = Color(0xFF7C5CFC)       // --purple

private val Bg = Color(0xFFF0F2F5)           // --bg 页背景
private val Card = Color(0xFFFFFFFF)         // --card
private val Border = Color(0xFFEBEEF5)       // --bd
private val Border2 = Color(0xFFDCDFE6)      // --bd2
private val TxtMain = Color(0xFF303133)      // --txt 主文字
private val TxtReg = Color(0xFF606266)       // --reg 常规
private val TxtSec = Color(0xFF909399)       // --sec 次要

private val HuicuiColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F5F7),
    onSecondaryContainer = Color(0xFF0A6570),
    tertiary = Purple,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF9B2C2C),
    background = Bg,
    onBackground = TxtMain,
    surface = Card,
    onSurface = TxtMain,
    surfaceVariant = Color(0xFFF7F8FA),
    onSurfaceVariant = TxtReg,
    outline = Border2,
    outlineVariant = Border,
    // M3 的 Card / NavigationBar 取的是 surfaceContainer* 而**不是** surface。
    // 不显式给值就落回 M3 基线色板——那是一组带紫调的中性灰，正是「界面到处冒紫」的来源。
    // 全部钉成白/浅灰：卡片是白的，页背景 Bg 是灰的，层次才立得住。
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Card,
    surfaceContainer = Card,
    surfaceContainerHigh = Color(0xFFF7F8FA),
    surfaceContainerHighest = Color(0xFFF0F2F5),
)

/** Material3 没有 success/warning/info 槽，用一个自定义 palette 补上，供状态徽标/KPI 用。 */
data class HuicuiSemanticColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val teal: Color,
    val purple: Color,
    val primaryLight: Color,
    val textReg: Color,
    val textSec: Color,
)

private val Semantic = HuicuiSemanticColors(
    success = Success, warning = Warning, danger = Danger, info = Info,
    teal = Teal, purple = Purple, primaryLight = PrimaryLight,
    textReg = TxtReg, textSec = TxtSec,
)

val LocalHuicuiColors = staticCompositionLocalOf { Semantic }

/** 便捷取用：MaterialTheme.huicui.success 等。 */
val huicui: HuicuiSemanticColors
    @Composable get() = LocalHuicuiColors.current

// ds-admin 圆角：卡片 8px、按钮/输入 4px（这里给 M3 的 small/medium/large 三档）
private val HuicuiShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
)

// KPI 数字用 tabular、粗体；标题 600；正文常规。对齐 ds-admin 字号层级。
private val HuicuiType = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun HuicuiTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHuicuiColors provides Semantic) {
        MaterialTheme(
            colorScheme = HuicuiColors,
            shapes = HuicuiShapes,
            typography = HuicuiType,
            content = content,
        )
    }
}
