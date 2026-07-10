package com.youzheng.huicui.app.data.case

import com.youzheng.huicui.app.api.models.CaseDetail
import com.youzheng.huicui.app.api.models.SeaCase
import com.youzheng.huicui.app.data.session.Permissions

/**
 * 界面能做什么，由**后端说了算**，客户端不自己推。纯函数，可离线单测。
 *
 * 实测澄清（勿凭直觉改）：
 *   · `Case.redacted` 的真实语义是「案件已关闭（SETTLED/WITHDRAWN）」，**不是**「你不是持有人」。
 *     未持有的公海案件 `/cases/{id}` 返回 `redacted=false` 且 `ownerName` 是真名，但 `contacts` 为空数组。
 *   · 能不能拨号看 `availableActions` 是否含 `call`，不看 `holderId`。
 *   · 公海列表的脱敏看 `SeaCase.contactMasked`；后端已经把 `ownerName` 换成 `***`，前端不再二次加工。
 *
 * 双重门控：后端给了动作，客户端还要有对应 permission 才渲染按钮 ——
 * 两者任一为假都不显示。这不是冗余：`availableActions` 是案件状态机的产物，
 * `permissions[]` 是账号权限的产物，二者会各自独立地变。
 */
object CaseActions {

    /** 电话是否可拨：后端脱敏时会给 `***` 之类的占位，绝不能把它塞进拨号盘。 */
    fun isDialable(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        if (phone.any { it == '*' }) return false
        return phone.count { it.isDigit() } >= 7
    }

    /** 契约把 availableActions 声明为 `List<string>`（无枚举），实测取值：follow/promise/payLink/call/release/ticket/claim。 */
    const val ACTION_CALL = "call"
    const val ACTION_RELEASE = "release"

    fun canCall(detail: CaseDetail, permissions: Set<String>): Boolean =
        ACTION_CALL in detail.availableActions.orEmpty() &&
            Permissions.CASE_CALL in permissions &&
            detail.contacts.orEmpty().any { isDialable(it.phone) }

    fun canRelease(detail: CaseDetail, permissions: Set<String>): Boolean =
        ACTION_RELEASE in detail.availableActions.orEmpty() &&
            Permissions.CASE_RELEASE in permissions

    /** 公海抢单。列表项不带 availableActions，故只能靠 permission + 竞争态。 */
    fun canClaim(seaCase: SeaCase, permissions: Set<String>): Boolean =
        Permissions.CASE_CLAIM in permissions &&
            seaCase.competitionState != SeaCase.CompetitionState.CLAIMED

    /** 持仓已满时后端会拒（BIZ_HOLD_CAP）；余量为 0 就别让人白点。 */
    fun claimBlockedByCapacity(seaCase: SeaCase): Boolean {
        val hint = seaCase.capacityHint ?: return false
        return hint <= 0
    }
}

/** 公海卡片的展示态。**不含任何电话字段**——公海列表里根本不该出现联系方式。 */
data class SeaCardState(
    val id: String,
    val acctNo: String,
    val ownerName: String,
    val room: String,
    val projectName: String,
    val dueCents: Int,
    val contactMasked: Boolean,
    val viewerCount: Int,
    val competitionState: String,
    val capacityHint: Int?,
) {
    companion object {
        fun from(c: SeaCase) = SeaCardState(
            id = c.id.orEmpty(),
            acctNo = c.acctNo.orEmpty(),
            ownerName = c.ownerName.orEmpty(),
            room = c.room.orEmpty(),
            projectName = c.projectName.orEmpty(),
            dueCents = c.dueCents ?: 0,
            contactMasked = c.contactMasked ?: true,   // 缺省从严：拿不准就当已脱敏
            viewerCount = c.viewerCount ?: 0,
            competitionState = c.competitionState?.value ?: "AVAILABLE",
            capacityHint = c.capacityHint,
        )
    }
}

/** 分（cents）→「1,234.56 元」。金额一律整数分传输，客户端不做浮点运算。 */
fun formatCents(cents: Int): String {
    val neg = cents < 0
    val v = if (neg) -cents.toLong() else cents.toLong()
    val yuan = v / 100
    val fen = v % 100
    val grouped = yuan.toString().reversed().chunked(3).joinToString(",").reversed()
    return (if (neg) "-" else "") + grouped + "." + fen.toString().padStart(2, '0')
}
