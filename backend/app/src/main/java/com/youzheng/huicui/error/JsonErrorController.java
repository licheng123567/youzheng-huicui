package com.youzheng.huicui.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 容器级错误统一 JSON 出口（替代 Whitelabel HTML）：
 * 框架层 400(畸形请求)/404(无 handler)/405(方法不支持) 等也返契约 Error{code,message,traceId} 信封，
 * 而非 HTML。否则 schemathesis 等客户端拿 HTML 当 JSON 解析失败（传输层噪声）。
 * 业务异常仍由 GlobalExceptionHandler 处理；此处只兜未进 @ControllerAdvice 的容器级错误。
 */
@RestController
public class JsonErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest req) {
        Object sc = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = (sc instanceof Integer i) ? i : 500;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", codeFor(status));
        body.put("message", messageFor(status));
        String traceId = MDC.get("traceId");
        if (traceId != null) body.put("traceId", traceId);
        return ResponseEntity.status(status).body(body);
    }

    private String codeFor(int s) {
        return switch (s) {
            case 400 -> "VALIDATION_422";   // 畸形请求归入校验类
            case 401 -> "AUTH_401";
            case 403 -> "PERM_403";
            case 404 -> "NOT_FOUND_404";
            case 405, 409 -> "STATE_409";
            case 422 -> "VALIDATION_422";
            default -> "VALIDATION_422";
        };
    }

    private String messageFor(int s) {
        return switch (s) {
            case 400 -> "请求格式错误";
            case 404 -> "资源或路径不存在";
            case 405 -> "方法不支持";
            default -> "请求无法处理(HTTP " + s + ")";
        };
    }
}
