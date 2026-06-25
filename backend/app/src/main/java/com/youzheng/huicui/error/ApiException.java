package com.youzheng.huicui.error;

/** 业务异常 → 由 GlobalExceptionHandler 映射为契约 Error 信封。 */
public class ApiException extends RuntimeException {
    public final BizError error;

    public ApiException(BizError error, String message) {
        super(message);
        this.error = error;
    }
}
