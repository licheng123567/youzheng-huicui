package com.youzheng.huicui.app.data.session

import com.youzheng.huicui.app.api.models.Me
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 登录后的主体信息。
 *
 * **界面内部一律按 `GET /me` 返回的 permissions[] 门控，不按角色名硬编码** ——
 * 角色的权限集是后端配的（`account.permissions` 可覆盖角色模板），客户端拿角色猜权限迟早猜错。
 *
 * 唯一的例外是 [AppRoles] 那道**入口门禁**：它是产品规则（谁该用这个 App），不是权限规则。
 */
class Session {
    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me.asStateFlow()

    fun set(me: Me?) { _me.value = me }
    fun clear() { _me.value = null }

    fun permissions(): Set<String> = _me.value?.permissions.orEmpty().toSet()

    fun has(permission: String): Boolean = permission in permissions()

    /** 当前主体的 account id —— 催收员端「我持有的」要拿它去过滤 `GET /cases?holderId=`。 */
    fun accountId(): String? = _me.value?.accountId
}

/**
 * App 现阶段用得到的权限点。写成常量避免各处敲错字符串。
 *
 * 催收员（CO）与物业协调员（PC）的权限集并不一样，界面必须按点门控而不是按角色：
 *   · `case.call`      两者都有 → 拨号与录音回传
 *   · `case.claim`     **仅催收员** → 公海抢单（协调员没有这一屏）
 *   · `case.release`   仅催收员
 *   · `case.follow`    两者都有 → 跟进/附件上传（M-A2 起用到）
 *   · `evidence.create` **仅协调员** → 送达存证（PR-2）
 */
object Permissions {
    const val CASE_CLAIM = "case.claim"
    const val CASE_CALL = "case.call"
    const val CASE_FOLLOW = "case.follow"
    const val CASE_RELEASE = "case.release"
    const val EVIDENCE_CREATE = "evidence.create"
}

/**
 * 入口门禁（BR-APP-01）：**App 只服务外勤作业角色 —— 催收员（CO）与物业协调员（PC）。**
 *
 * 为什么这里必须按角色而不是按权限：物业负责人（PL）**也有 `case.call`**
 * （BR-M4-01a 允许他给关联案件打电话），纯按权限门控会把他放进来。
 * 但他是管理岗，日常在网页端作业。这是产品规则，不是权限规则。
 *
 * 平台超管/运营（SA/SE）、服务商负责人（VL）同理——VL 连 `case.call` 都没有。
 */
object AppRoles {
    const val COLLECTOR = "CO"
    const val COORDINATOR = "PC"

    private val ALLOWED = setOf(COLLECTOR, COORDINATOR)

    fun canEnter(role: String?): Boolean = role in ALLOWED

    /** 角色对外一律显示中文名，不露裸码。 */
    fun label(role: String?): String = when (role) {
        "SA" -> "平台超管"
        "SE" -> "平台运营"
        "PL" -> "物业负责人"
        COORDINATOR -> "物业协调员"
        "VL" -> "服务商负责人"
        COLLECTOR -> "催收员"
        else -> role.orEmpty()
    }
}
