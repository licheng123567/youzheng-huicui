package com.youzheng.huicui.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等键拦截器（骨架）：写操作带 Idempotency-Key → 同键已成功处理过 → 重放返 409。
 * 关键(审计 H-2)：仅在请求**成功(2xx)后**登记键(afterCompletion)，失败请求不登记 → 客户端可用同键安全重试。
 * 地基期用内存 Map(无 TTL/单实例)；生产应换 Redis/DB（键=key+method+path+body哈希，存响应快照按 TTL）。
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String ATTR = IdempotencyInterceptor.class.getName() + ".composite";
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final ObjectMapper om = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!WRITE.contains(req.getMethod())) return true;
        String key = req.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) return true;   // 未带键不强制（契约为可选）
        String composite = key + " " + req.getMethod() + " " + req.getServletPath();
        if (seen.containsKey(composite)) {
            // 已成功处理过 → 重放：返契约 Error 信封 JSON（非裸 409，否则客户端拿无 body/无 Content-Type 响应解析失败）
            res.setStatus(409);
            res.setHeader("X-Idempotency-Replay", "true");
            res.setContentType("application/json;charset=UTF-8");
            om.writeValue(res.getWriter(), Map.of(
                    "code", "STATE_409", "message", "幂等键重放：请求已处理",
                    "traceId", String.valueOf(MDC.get("traceId"))));
            return false;   // 骨架：拒绝重放（生产应返回首次响应快照）
        }
        req.setAttribute(ATTR, composite);   // 暂存，待 afterCompletion 按结果决定是否登记
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        Object composite = req.getAttribute(ATTR);
        // 仅成功(2xx)才登记键 → 失败(4xx/5xx)不占键，客户端可用同键安全重试(幂等语义)。
        if (composite != null && res.getStatus() >= 200 && res.getStatus() < 300) {
            seen.putIfAbsent((String) composite, System.currentTimeMillis());
        }
    }
}
