package com.youzheng.huicui.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/** 统一错误响应：契约 Error{code,message,traceId,details[]} + 状态码映射。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 契约 Error{code,message,traceId?,details?[]}：省略 null（details 为非空数组类型，不可序列化 null）。
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String code, String message, String traceId, List<Map<String, Object>> details) {}

    private ResponseEntity<ApiError> build(BizError e, String message, List<Map<String, Object>> details) {
        String traceId = MDC.get("traceId");
        return ResponseEntity.status(e.httpStatus).body(new ApiError(e.code, message, traceId, details));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return build(ex.error, ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.<String, Object>of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        return build(BizError.VALIDATION_422, "请求校验失败", details);
    }

    @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
    public ResponseEntity<ApiError> handleNotFound(Exception ex) {
        return build(BizError.NOT_FOUND_404, "资源不存在", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        // 兜底：不泄露内部细节，仅 traceId 可追
        return build(BizError.VALIDATION_422, "请求无法处理: " + ex.getClass().getSimpleName(), null);
    }
}
