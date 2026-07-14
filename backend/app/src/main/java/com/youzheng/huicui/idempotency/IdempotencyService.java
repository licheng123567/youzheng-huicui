package com.youzheng.huicui.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等键（落库版）。取代原来的 {@code ConcurrentHashMap}。
 *
 * <p><b>为什么内存版是坏的</b>（三重致命，少说一条都不足以说明严重性）：
 * <ul>
 *   <li><b>跨事务边界的 check-then-act</b>：老实现在 {@code preHandle} 里查、在
 *       {@code afterCompletion}（**业务事务提交之后**）才登记。两个并发的同键请求都能通过检查、
 *       都能提交 —— <b>单实例下幂等就已经不成立</b>，跟多不多副本无关。</li>
 *   <li><b>单 JVM 内存</b>：多副本（哪怕只是滚动发布期间新旧 pod 并存）直接双写。这条<b>堵死水平扩容</b>。</li>
 *   <li><b>无 TTL</b>：堆无限增长。</li>
 * </ul>
 *
 * <p><b>现在怎么做对</b>：<b>唯一约束就是那把锁</b>。抢键靠一条 {@code INSERT}（唯一索引
 * {@code uq_idempotency_key}）—— 由数据库裁决谁先谁后，而不是靠"两次读之间没人插队"的运气。
 *
 * <p><b>失败不占键</b>：业务抛异常时把键释放掉，客户端可以用同一个键安全重试。
 * 否则一次网络抖动就会把这个键永久烧掉，用户再也提交不了这笔钱。
 */
@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 抢占幂等键：抢到返回记录 id；已被占用（无论是处理中还是已完成）返回 {@code null}。
     *
     * <p><b>必须 REQUIRES_NEW</b>：键要在**业务事务之外**独立提交。跟业务同事务的话，
     * 业务一回滚键也跟着回滚，"抢占"就形同虚设。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long claim(String key, String method, String path) {
        try {
            return jdbc.queryForObject(
                    "INSERT INTO idempotency_record(idem_key, method, path) VALUES (?, ?, ?) RETURNING id",
                    Long.class, key, method, path);
        } catch (DuplicateKeyException e) {
            return null;   // 已被占用 → 调用方按重放处理
        }
    }

    /** 业务失败：释放键，客户端可用同键安全重试（成功则**不**释放，键就此生效）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(long recordId) {
        jdbc.update("DELETE FROM idempotency_record WHERE id = ?", recordId);
    }

    /** 过期清理（内存版没有 TTL、堆会无限涨；落库版靠它）。 */
    @Transactional
    public int purgeOlderThanDays(int days) {
        return jdbc.update(
                "DELETE FROM idempotency_record WHERE created_at < now() - (? || ' days')::interval", days);
    }
}
