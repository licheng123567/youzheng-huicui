package com.youzheng.huicui.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// docs/ui/15-App页面规划.md §4：ds-admin 设计令牌 → Compose 常量
private val DsPrimary = Color(0xFF1E5EFF)
private val DsDanger = Color(0xFFE5484D)
private val DsSurface = Color(0xFFF7F8FA)

private val HuicuiColors = lightColorScheme(
    primary = DsPrimary,
    error = DsDanger,
    background = DsSurface,
)

@Composable
fun HuicuiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HuicuiColors, content = content)
}
