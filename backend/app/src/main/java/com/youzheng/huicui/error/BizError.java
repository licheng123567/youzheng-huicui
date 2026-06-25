package com.youzheng.huicui.error;

/** 错误码 → HTTP 状态，对齐契约 Error.code enum 与 401/403/404/409/422 口径。 */
public enum BizError {
    AUTH_401(401, "AUTH_401"),
    PERM_403(403, "PERM_403"),
    NOT_FOUND_404(404, "NOT_FOUND_404"),
    STATE_409(409, "STATE_409"),
    VALIDATION_422(422, "VALIDATION_422"),
    BIZ_WRONG_SETTLE_SIDE(403, "BIZ_WRONG_SETTLE_SIDE"),
    BIZ_NO_VOUCHER(422, "BIZ_NO_VOUCHER");

    public final int httpStatus;
    public final String code;

    BizError(int httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
