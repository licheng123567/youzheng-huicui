package com.youzheng.huicui.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 单实例护栏。
 *
 * <p><b>为什么需要它</b>：登录票据、短信验证码、发码冷却这三样，目前都存在<b>进程内存</b>里
 * （{@code AuthController} 的三个 ConcurrentHashMap）。单实例下这完全正确；
 * 可一旦跑起第二个副本（哪怕只是滚动发布期间新旧容器并存），失败方式是<b>静默</b>的：
 * <ul>
 *   <li>用户在 A 实例拿到验证码，请求被负载均衡打到 B 实例 → <b>「验证码不存在」</b>，而他明明刚收到；</li>
 *   <li>选账号登录的票据同理，多账号用户会随机登不进去；</li>
 *   <li>发码冷却按实例各算一份 → <b>冷却形同虚设</b>，而短信是<b>要花钱</b>的。</li>
 * </ul>
 * 这些都不会在日志里留下任何痕迹，你只会收到"系统时好时坏"的投诉。
 *
 * <p><b>所以：宁可起不来，也不要静默地半坏。</b>
 * 检测到同一个库上已有另一个活着的实例 → 拒绝启动，并说清楚要么回到单实例、要么把这三样搬到 Redis。
 *
 * <p><b>不会误伤正常重启/升级</b>：优雅停机时（{@code @PreDestroy}）会把自己的心跳行删掉，
 * 所以 compose 重建容器这条路径是干净的。只有<b>真的并存</b>（或上一个实例被 SIGKILL 且不足
 * {@value #STALE_SECONDS} 秒）才会拦。后者可以等一会儿再起，或显式设
 * {@code HUICUI_ALLOW_MULTI_INSTANCE=true} 放行（那时你必须自己确认已经解决了共享状态问题）。
 */
@Component
public class SingleInstanceGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    /** 心跳超过这么久没更新，就认为那个实例已经死了。 */
    static final int STALE_SECONDS = 45;

    private final JdbcTemplate jdbc;
    private final boolean allowMulti;
    private final String instanceId = UUID.randomUUID().toString();

    public SingleInstanceGuard(JdbcTemplate jdbc,
                               @Value("${huicui.allow-multi-instance:false}") boolean allowMulti) {
        this.jdbc = jdbc;
        this.allowMulti = allowMulti;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 先清掉已经死透的心跳（上次被 kill -9 之类）
        jdbc.update("DELETE FROM app_instance WHERE last_seen < now() - (? || ' seconds')::interval",
                STALE_SECONDS);

        Integer alive = jdbc.queryForObject(
                "SELECT count(*) FROM app_instance WHERE instance_id <> ?", Integer.class, instanceId);

        if (alive != null && alive > 0 && !allowMulti) {
            throw new IllegalStateException("\n"
                    + "==================== 检测到多个实例 ====================\n"
                    + "同一个数据库上已经有 " + alive + " 个活着的后端实例。\n"
                    + "\n"
                    + "登录票据 / 短信验证码 / 发码冷却目前存在**进程内存**里，多实例下会静默半坏：\n"
                    + "  · 用户在 A 实例拿到验证码、请求打到 B 实例 → 「验证码不存在」（他明明刚收到）\n"
                    + "  · 多账号用户选账号登录会随机失败\n"
                    + "  · 发码冷却按实例各算一份 → 冷却形同虚设，而短信是要花钱的\n"
                    + "这些都不会在日志里留痕，你只会收到「系统时好时坏」的投诉。\n"
                    + "\n"
                    + "处置：\n"
                    + "  · 回到单实例（推荐）；或\n"
                    + "  · 把上述三样搬到 Redis 之后，再设 HUICUI_ALLOW_MULTI_INSTANCE=true 放行。\n"
                    + "  （若上一个实例是被 kill -9 的，等 " + STALE_SECONDS + " 秒心跳过期后再起即可。）\n"
                    + "=========================================================");
        }

        if (alive != null && alive > 0) {
            log.warn("[Instance] HUICUI_ALLOW_MULTI_INSTANCE=true：已放行多实例，"
                    + "但登录票据/验证码/冷却仍在进程内存里 —— 你必须自己确保已解决共享状态问题。");
        }

        jdbc.update("INSERT INTO app_instance(instance_id, started_at, last_seen)"
                + " VALUES (?, now(), now()) ON CONFLICT (instance_id) DO UPDATE SET last_seen = now()",
                instanceId);
        log.info("[Instance] 本实例 {} 已登记（单实例护栏生效）", instanceId);
    }

    /** 心跳。比 STALE_SECONDS 短得多，免得自己被别人当成死的。 */
    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        jdbc.update("UPDATE app_instance SET last_seen = now() WHERE instance_id = ?", instanceId);
    }

    /**
     * 优雅停机时把自己摘掉 —— 这样 compose 重建容器（先停旧、再起新）不会被自己的心跳挡住。
     * 没有这一步，每次升级都要干等 45 秒。
     */
    @PreDestroy
    public void deregister() {
        try {
            jdbc.update("DELETE FROM app_instance WHERE instance_id = ?", instanceId);
        } catch (RuntimeException e) {
            log.warn("[Instance] 摘除心跳失败（下次启动会靠超时清理）：{}", e.toString());
        }
    }
}
