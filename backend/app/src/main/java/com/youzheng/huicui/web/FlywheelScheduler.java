package com.youzheng.huicui.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 话术飞轮定时结算（BR-M5-12 环6/环7 的自动挡）。
 *
 * <p>飞轮的前三环（承诺归因 → 兑现/回款沉淀）本来就随业务自动发生，只有「结算」这一下
 * 原先必须平台运营到话术库页面手点 {@code POST /script-lib/recompute}。**指望人每天记得点一次
 * 是不现实的**：漏点的那些天，话术统计停在旧快照上，跑赢的 AI 变体也永远晋升不了 —— 飞轮就停转了。
 *
 * <p>能安全上定时器，是因为 {@link FlywheelService#recomputeAll} 是纯聚合、幂等的：
 * 重算只按 {@code promise.script_id} 的归因回填统计字段（跑一遍和跑一百遍结果相同），
 * 晋升只碰 {@code source=AI_MINED} 且已达阈值的候选变体、保留旧文本可回滚、且每条都写审计。
 * 专家人工录入的话术永不自动晋升。
 *
 * <p>手动按钮保留：改了阈值、补了归因、想立刻看效果时不必等到明早。两条路径同一个入口，
 * 差别只在审计里的 actor（人 vs system）。
 */
@Component
public class FlywheelScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlywheelScheduler.class);

    private final FlywheelService flywheel;
    private final boolean enabled;

    public FlywheelScheduler(FlywheelService flywheel,
                             @Value("${huicui.flywheel.auto:true}") boolean enabled) {
        this.flywheel = flywheel;
        this.enabled = enabled;
    }

    /** 每天 04:00（错开 03:00 备份、03:30 留存清理）。actor=null → 审计记 system。 */
    @Scheduled(cron = "${huicui.flywheel.cron:0 0 4 * * *}")
    public void tick() {
        if (!enabled) return;
        try {
            FlywheelService.Result r = flywheel.recomputeAll(null);
            if (r.recomputed() > 0 || r.promoted() > 0) {
                log.info("[Flywheel] 定时结算：回流重算 {} 条话术统计，自动晋升 {} 条达标 AI 变体",
                        r.recomputed(), r.promoted());
            }
        } catch (RuntimeException e) {
            // 吞掉：飞轮结算失败不该拖垮实例，且下次（明天/手点）会重跑 —— 它是幂等的。
            log.error("[Flywheel] 定时结算异常（幂等，下次重试）", e);
        }
    }
}
