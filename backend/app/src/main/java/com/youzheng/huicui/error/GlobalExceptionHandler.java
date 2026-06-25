package com.youzheng.huicui.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 契约 Error{code,message,traceId?,details?[]}：省略 null（details 为非空数组类型，不可序列化 null）。
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String code, String message, String traceId, List<Map<String, Object>> details) {}

    private ResponseEntity<ApiError> build(BizError e, String message, List<Map<String, Object>> details) {
        String traceId = MDC.get("traceId");
        return ResponseEntity.status(e.httpStatus).body(new ApiError(e.code, sanitize(message), traceId, details));
    }

    /** 净化错误消息：剥离未配对代理与控制字符——避免回显未净化的用户输入(如非法 unicode 路径 id)产出非法 JSON。 */
    private static String sanitize(String s) {
        return s == null ? null : s.replaceAll("[\\x00-\\x1F\\x7F\\uD800-\\uDFFF]", "");
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

    /** 唯一约束冲突(如并发生成同单号 payment_request.no)→ 409 可重试，而非兜底 422(审计 H-3)。 */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(Exception ex) {
        return build(BizError.STATE_409, "资源冲突（并发或重复），请重试", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        // 兜底：对客户端不泄露内部细节(仅 traceId+异常类名)，但服务端必须记完整堆栈——
        // 否则真实 bug(NPE/约束冲突/SQL 错)被伪装成 422 业务校验，线上无法定位(审计 M-1)。
        log.error("未预期异常落兜底(traceId={})，对外返 422：", MDC.get("traceId"), ex);
        return build(BizError.VALIDATION_422, "请求无法处理: " + ex.getClass().getSimpleName(), null);
    }
}
