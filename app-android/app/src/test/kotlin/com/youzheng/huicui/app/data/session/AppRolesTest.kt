package com.youzheng.huicui.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口门禁（BR-APP-01）：App 只服务外勤作业角色 —— 催收员（CO）与物业协调员（PC）。
 *
 * 这里必须按**角色**而不是按权限，原因很具体：
 * 物业负责人（PL）**也有 `case.call`**（BR-M4-01a 允许他给关联案件打电话），
 * 纯按权限门控会把这个管理岗放进来。
 */
class AppRolesTest {

    @Test
    fun `只有催收员与物业协调员能进`() {
        assertTrue(AppRoles.canEnter("CO"))
        assertTrue(AppRoles.canEnter("PC"))
    }

    @Test
    fun `物业负责人被挡在门外 —— 尽管他也有 case_call 权限`() {
        assertFalse(
            "PL 有 case.call（BR-M4-01a），若按权限门控会被放进来。这里必须按角色。",
            AppRoles.canEnter("PL"),
        )
    }

    @Test
    fun `平台与服务商的管理角色都被挡在门外`() {
        assertFalse(AppRoles.canEnter("SA"))
        assertFalse(AppRoles.canEnter("SE"))
        assertFalse(AppRoles.canEnter("VL"))
    }

    @Test
    fun `未知角色与空值一律不放行 —— 默认拒绝`() {
        assertFalse(AppRoles.canEnter(null))
        assertFalse(AppRoles.canEnter(""))
        assertFalse(AppRoles.canEnter("ADMIN"))
        assertFalse(AppRoles.canEnter("co"))   // 大小写敏感，不做模糊匹配
    }

    @Test
    fun `六角色都有中文名 不露裸码`() {
        assertEquals("平台超管", AppRoles.label("SA"))
        assertEquals("平台运营", AppRoles.label("SE"))
        assertEquals("物业负责人", AppRoles.label("PL"))
        assertEquals("物业协调员", AppRoles.label("PC"))
        assertEquals("服务商负责人", AppRoles.label("VL"))
        assertEquals("催收员", AppRoles.label("CO"))
    }

    @Test
    fun `未知角色码原样返回 不崩`() {
        assertEquals("XX", AppRoles.label("XX"))
        assertEquals("", AppRoles.label(null))
    }
}
