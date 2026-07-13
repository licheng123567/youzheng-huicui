package com.youzheng.huicui.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

/**
 * 幂等键拦截器：写操作带 {@code Idempotency-Key} → 同键重复 → 409。
 *
 * <p><b>这里曾经是一个 {@code ConcurrentHashMap}</b>，而且是**跨事务边界的 check-then-act**：
 * {@code preHandle} 里查、{@code afterCompletion}（业务事务**提交之后**）才登记。
 * 两个并发的同键请求都能通过检查、都能提交 —— 单实例下幂等就已经不成立；多副本更是直接双写。
 * 现在改成落库抢占（见 {@link IdempotencyService}）：<b>唯一约束就是那把锁</b>。
 *
 * <p>抢键在 {@code preHandle}（业务事务尚未开始）、释放在 {@code afterCompletion}（已提交/已回滚）：
 * 成功就把键留着（后续同键重放 → 409），失败就释放（客户端可用同键安全重试）。
 *
 * <p><b>本拦截器只保证"不会被执行两次"</b>。回款那种"重放要拿回首次那笔钱"的真幂等语义，
 * 由 {@code repay_line.idem_key} 的唯一索引在业务表上直接给出首次那一行 ——
 * 见 {@code PayReduceRepayM4Controller.createRepayLine}。
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String ATTR = IdempotencyInterceptor.class.getName() + ".recordId";

    /**
     * 自己实现了**真幂等**的端点，本拦截器让路。
     *
     * <p>契约对 Idempotency-Key 的原话是「同 key 重复请求**返回首次结果**」，而本拦截器只会回 409。
     * 对回款这种端点，409 是不够的：协调员双击之后，第二次请求必须拿回**第一次那笔回款**，
     * 而不是一个"重复了"的错误 —— 否则界面无从知道那笔钱到底记上没有。
     * 该端点用 {@code repay_line.idem_key} 的唯一索引在业务表上直接给出首次那一行。
     */
    private static boolean selfIdempotent(String method, String path) {
        return "POST".equalsIgnoreCase(method) && path.endsWith("/repay-lines");
    }

    private final IdempotencyService idem;
    private final ObjectMapper om = new ObjectMapper();

    public IdempotencyInterceptor(IdempotencyService idem) {
        this.idem = idem;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!WRITE.contains(req.getMethod())) return true;
        if (selfIdempotent(req.getMethod(), req.getServletPath())) return true;
        String key = req.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) return true;   // 未带键不强制（契约声明为可选）

        Long recordId = idem.claim(key, req.getMethod(), req.getServletPath());
        if (recordId == null) {
            // 没抢到：同键要么正在处理、要么已经成功过。两种都不能再执行第二遍。
            res.setStatus(409);
            res.setHeader("X-Idempotency-Replay", "true");
            res.setContentType("application/json;charset=UTF-8");
            om.writeValue(res.getWriter(), Map.of(
                    "code", "STATE_409", "message", "幂等键重放：请求已处理",
                    "traceId", String.valueOf(MDC.get("traceId"))));
            return false;
        }
        req.setAttribute(ATTR, recordId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        Object recordId = req.getAttribute(ATTR);
        if (recordId == null) return;
        // 只有成功(2xx)才让键**留下**；失败则释放 → 客户端可用同键安全重试，
        // 否则一次 500 就把这个键永久烧掉，那笔钱再也提交不上来。
        boolean ok = res.getStatus() >= 200 && res.getStatus() < 300 && ex == null;
        if (!ok) idem.release((Long) recordId);
    }
}
