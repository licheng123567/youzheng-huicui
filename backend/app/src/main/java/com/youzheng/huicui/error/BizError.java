package com.youzheng.huicui.error;

/** 错误码 → HTTP 状态，对齐契约 Error.code enum 与 401/403/404/409/422 口径。 */
public enum BizError {
    AUTH_401(401, "AUTH_401"),
    PERM_403(403, "PERM_403"),
    NOT_FOUND_404(404, "NOT_FOUND_404"),
    STATE_409(409, "STATE_409"),
    VALIDATION_422(422, "VALIDATION_422"),
    BIZ_WRONG_SETTLE_SIDE(403, "BIZ_WRONG_SETTLE_SIDE"),
    BIZ_NO_VOUCHER(422, "BIZ_NO_VOUCHER"),
    // ── M3 派单/抢单状态机 ──
    BIZ_ALREADY_CLAIMED(409, "BIZ_ALREADY_CLAIMED"),     // 抢单并发：已被他人占用
    BIZ_HOLD_CAP(409, "BIZ_HOLD_CAP"),                   // 催收员私海持有上限 CFG-HOLDCAP
    BIZ_OPEN_RATE_REQUIRED(409, "BIZ_OPEN_RATE_REQUIRED"), // 开放抢单前批次 open_rate 未设 BR-M9-18
    BIZ_CAP_EXCEEDED(409, "BIZ_CAP_EXCEEDED"),           // 服务商持有余量不足 BR-M3-23(CFG-HOLDCAP)
    BIZ_REDISPATCH_GUARD(409, "BIZ_REDISPATCH_GUARD"),   // 单案再派护栏①：目标=原退回服务商/已停用 US-M3-02
    BIZ_PAYOUT_INVERT(422, "BIZ_PAYOUT_INVERT"),         // 防倒挂：付佣比例 > 收佣比例
    // ── M4 缴费链接/回款 ──
    BIZ_SMS_COOLDOWN(409, "BIZ_SMS_COOLDOWN"),           // 同案缴费短信冷却未到 BR-M4-14a
    BIZ_QUOTA_EXHAUSTED(409, "BIZ_QUOTA_EXHAUSTED"),     // 短信/分钟余量不足（M9 预付费，地基期可不触发）
    SERVICE_503(503, "SERVICE_503");                      // 服务暂不可用（prod SMS 通道未接入等）

    public final int httpStatus;
    public final String code;

    BizError(int httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
