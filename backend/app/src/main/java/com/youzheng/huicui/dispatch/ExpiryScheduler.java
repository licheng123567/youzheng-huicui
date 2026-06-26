package com.youzheng.huicui.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 公海定时器自动到期调度（CFG-T2/TC）。间隔 huicui.expiry.fixedDelayMs（默认 60s；可配，验证可调短）。
 * initialDelay 让应用迁移/种子完成后再首跑。异常吞掉不影响下次（log 即可）。
 */
@Component
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);
    private final ExpiryService expiry;

    public ExpiryScheduler(ExpiryService expiry) { this.expiry = expiry; }

    @Scheduled(fixedDelayString = "${huicui.expiry.fixedDelayMs:60000}", initialDelayString = "${huicui.expiry.initialDelayMs:15000}")
    public void tick() {
        try {
            ExpiryService.ExpiryResult r = expiry.runExpiry();
            if (r.releasedTC() > 0 || r.returnedT2() > 0) {
                log.info("公海到期：自动释放 {} 件(CFG-TC)，自动退回 {} 件(CFG-T2)", r.releasedTC(), r.returnedT2());
            }
        } catch (RuntimeException e) {
            log.error("公海到期任务异常（下次重试）", e);
        }
    }
}
