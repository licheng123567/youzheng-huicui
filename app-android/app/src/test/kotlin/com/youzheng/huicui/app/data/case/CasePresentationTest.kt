package com.youzheng.huicui.app.data.case

import com.youzheng.huicui.app.api.models.Case
import com.youzheng.huicui.app.api.models.CaseDetail
import com.youzheng.huicui.app.api.models.Contact
import com.youzheng.huicui.app.api.models.SeaCase
import com.youzheng.huicui.app.data.session.Permissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验收③（公海脱敏）与权限门控。纯 JVM。
 *
 * 这些断言不是凭直觉写的，是照着真后端返回的形状写的：
 *   · `/sea` 里 `ownerName` 已是 `***`、`contactMasked=true`、`Case` 根本没有 phone 字段。
 *   · `/cases/{id}` 未持有时 `contacts=[]`；已结案时 `redacted=true` 且 `phone` 为 `***`。
 *   · 能不能拨号看 `availableActions` 是否含 `call`（实测取值 follow/promise/payLink/call/release/ticket/claim）。
 */
class CasePresentationTest {

    private val coPermissions = setOf(
        Permissions.CASE_CLAIM, Permissions.CASE_CALL,
        Permissions.CASE_FOLLOW, Permissions.CASE_RELEASE,
    )

    // ── 验收③：公海脱敏 ─────────────────────────────────────────────────────

    @Test
    fun `公海卡片不含任何电话字段`() {
        // SeaCardState 是 UI 唯一的数据来源；它连一个电话字段都没有，
        // 因此公海列表在结构上就不可能渲染出明文号码。
        val fields = SeaCardState::class.java.declaredFields.map { it.name }
        assertFalse(
            "SeaCardState 不得出现任何电话/联系方式字段，实际字段：$fields",
            fields.any { it.contains("phone", true) || it.contains("contact", true) && it != "contactMasked" },
        )
    }

    @Test
    fun `contactMasked 缺省从严 —— 后端没说就当已脱敏`() {
        val card = SeaCardState.from(SeaCase(id = "1", contactMasked = null))
        assertTrue(card.contactMasked)
    }

    @Test
    fun `公海卡片如实呈现后端已脱敏的 ownerName 不做任何还原`() {
        val card = SeaCardState.from(SeaCase(id = "1", ownerName = "***", contactMasked = true))
        assertEquals("***", card.ownerName)
    }

    // ── 拨号门控 ────────────────────────────────────────────────────────────

    @Test
    fun `脱敏占位号码绝不可拨`() {
        assertFalse(CaseActions.isDialable("***"))
        assertFalse(CaseActions.isDialable("138****1234"))
        assertFalse(CaseActions.isDialable(""))
        assertFalse(CaseActions.isDialable(null))
        assertFalse(CaseActions.isDialable("12345"))          // 位数不够
        assertTrue(CaseActions.isDialable("13900000099"))
        assertTrue(CaseActions.isDialable("010-1234 5678"))   // 含分隔符仍可拨
    }

    @Test
    fun `后端给了 call 且有真号才可拨`() {
        val d = detail(actions = listOf("follow", "call"), phones = listOf("13900000099"))
        assertTrue(CaseActions.canCall(d, coPermissions))
    }

    @Test
    fun `后端没给 call 时 即便有真号也不可拨`() {
        val d = detail(actions = listOf("follow"), phones = listOf("13900000099"))
        assertFalse(CaseActions.canCall(d, coPermissions))
    }

    @Test
    fun `账号缺 case_call 权限时不可拨 —— 与后端动作是且的关系`() {
        val d = detail(actions = listOf("call"), phones = listOf("13900000099"))
        assertFalse(CaseActions.canCall(d, coPermissions - Permissions.CASE_CALL))
    }

    @Test
    fun `已结案脱敏后 号码是星号 即便 call 在动作里也不可拨`() {
        val d = detail(actions = listOf("call"), phones = listOf("***"))
        assertFalse(CaseActions.canCall(d, coPermissions))
    }

    @Test
    fun `未持有的公海案件 contacts 为空 不可拨`() {
        val d = detail(actions = listOf("claim"), phones = emptyList())
        assertFalse(CaseActions.canCall(d, coPermissions))
    }

    // ── 验收：权限门控（缺 case.claim 不渲染抢单） ──────────────────────────

    @Test
    fun `缺 case_claim 权限时不可抢单`() {
        val sea = SeaCase(id = "1", competitionState = SeaCase.CompetitionState.AVAILABLE)
        assertFalse(CaseActions.canClaim(sea, coPermissions - Permissions.CASE_CLAIM))
        assertTrue(CaseActions.canClaim(sea, coPermissions))
    }

    @Test
    fun `已被抢走的案件不可再抢`() {
        val sea = SeaCase(id = "1", competitionState = SeaCase.CompetitionState.CLAIMED)
        assertFalse(CaseActions.canClaim(sea, coPermissions))
    }

    @Test
    fun `持仓余量为 0 时抢单被拦 余量为 null 时不拦`() {
        assertTrue(CaseActions.claimBlockedByCapacity(SeaCase(id = "1", capacityHint = 0)))
        assertFalse(CaseActions.claimBlockedByCapacity(SeaCase(id = "1", capacityHint = 3)))
        assertFalse(CaseActions.claimBlockedByCapacity(SeaCase(id = "1", capacityHint = null)))
    }

    // ── 金额 ────────────────────────────────────────────────────────────────

    @Test
    fun `分转元 千分位 两位小数`() {
        assertEquals("0.00", formatCents(0))
        assertEquals("2,800.00", formatCents(280000))
        assertEquals("1.05", formatCents(105))
        assertEquals("12,345,678.90", formatCents(1234567890))
        assertEquals("-1.00", formatCents(-100))
    }

    private fun detail(actions: List<String>, phones: List<String>) = CaseDetail(
        case = Case(id = "7"),
        contacts = phones.mapIndexed { i, p -> Contact(id = "$i", phone = p) },
        availableActions = actions,
    )
}
