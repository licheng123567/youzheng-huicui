package com.youzheng.huicui.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等键拦截器（骨架）：写操作带 Idempotency-Key → 同键 5xx 内重放返 409 提示重复。
 * 地基期用内存 Map；生产应换 Redis/DB（键=key+method+path+body哈希，存响应快照按 TTL）。
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!WRITE.contains(req.getMethod())) return true;
        String key = req.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) return true;   // 未带键不强制（契约为可选）
        String composite = key + " " + req.getMethod() + " " + req.getServletPath();
        Long first = seen.putIfAbsent(composite, System.currentTimeMillis());
        if (first != null) {
            res.setStatus(409);
            res.setHeader("X-Idempotency-Replay", "true");
            return false;   // 骨架：拒绝重放（生产应返回首次响应快照）
        }
        return true;
    }
}
