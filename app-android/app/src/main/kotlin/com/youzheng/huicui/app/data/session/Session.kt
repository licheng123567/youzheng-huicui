package com.youzheng.huicui.app.data.session

import com.youzheng.huicui.app.api.models.Me
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录后的主体信息。界面**只按 `GET /me` 返回的 permissions[] 门控**，
 * 不按角色名硬编码 —— 角色的权限集是后端配的（`account.permissions` 可覆盖角色模板），
 * 客户端拿角色猜权限迟早猜错。
 */
class Session {
    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me.asStateFlow()

    fun set(me: Me?) { _me.value = me }
    fun clear() { _me.value = null }

    fun permissions(): Set<String> = _me.value?.permissions.orEmpty().toSet()

    fun has(permission: String): Boolean = permission in permissions()
}

/** CO 的 8 项权限里，App 现阶段用得到的几个。写成常量避免各处敲错字符串。 */
object Permissions {
    const val CASE_CLAIM = "case.claim"
    const val CASE_CALL = "case.call"
    const val CASE_FOLLOW = "case.follow"
    const val CASE_RELEASE = "case.release"
}
