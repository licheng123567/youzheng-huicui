package com.youzheng.huicui.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * prod profile 启动软告警。**硬校验不在这里**——见 {@link ProdEnvironmentGuard}，
 * 它作为 EnvironmentPostProcessor 抢在任何 bean 创建之前跑（否则 JwtService 构造器会先抛
 * JJWT 的 WeakKeyException，运维只看得到天书，看不到可操作的中文提示）。
 *
 * <p>本类只做一件事：把「不配置也能跑」这个最大的风险面，在启动日志里吼出来。
 * 短信不通 → 验证码与缴费链接触达不了业主；存证不通 → 发起存证只落占位记录、出的证没有法律效力。
 * 短信开着但处于 dry-run → 更坏：看起来一切正常，其实一条都没发出去。
 * 三者都不硬失败（允许先上内网灰度 / 预发演练），但绝不能悄无声息。
 */
@Profile("prod")
@Configuration
public class ProdGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdGuard.class);

    @Value("${huicui.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${huicui.sms.dry-run:false}")
    private boolean smsDryRun;

    @Value("${huicui.ebaoquan.enabled:false}")
    private boolean ebqEnabled;

    @PostConstruct
    public void announce() {
        if (!smsEnabled) {
            log.warn("[ProdGuard] ⚠ 短信通道未启用（HUICUI_SMS_ENABLED=false）："
                    + "验证码与缴费链接无法触达业主，/auth/sms-code 将返回 502。仅适用于内网灰度。");
        } else if (smsDryRun) {
            // 「以为在发短信、其实一条没发」是最贵的静默失败，比压根没启用还危险。
            log.warn("======================================================================");
            log.warn("[ProdGuard] ⚠ 短信处于 DRY-RUN：不会真的发出任何短信，且验证码会被写入日志！");
            log.warn("[ProdGuard] 若这是正式生产环境，请立刻设置 HUICUI_SMS_DRY_RUN=false 并重启。");
            log.warn("======================================================================");
        }
        if (!ebqEnabled) {
            log.warn("[ProdGuard] ⚠ 存证通道未启用（HUICUI_EBQ_ENABLED=false）："
                    + "发起存证只落占位记录，出的证没有法律效力。仅适用于内网灰度。");
        }
        log.info("[ProdGuard] ✅ 生产启动自检通过（数据源/JWT 已注入；短信={}，存证={}）",
                smsEnabled ? (smsDryRun ? "启用·DRY-RUN" : "启用") : "未启用",
                ebqEnabled ? "启用" : "未启用");
    }
}
