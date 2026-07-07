package com.youzheng.huicui.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 易保全备案态轮询调度（仿 dispatch/ExpiryScheduler）。间隔 huicui.evidence.poll.fixed-delay-ms（默认 60s）。
 * 未接入易保全（enabled=false）时 pollOnce() 直接返 0，空转无副作用。异常吞掉不影响下次。
 */
@Component
public class EvidencePollScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvidencePollScheduler.class);
    private final EvidencePollService poll;

    public EvidencePollScheduler(EvidencePollService poll) {
        this.poll = poll;
    }

    @Scheduled(fixedDelayString = "${huicui.evidence.poll.fixed-delay-ms:60000}",
            initialDelayString = "${huicui.evidence.poll.initial-delay-ms:20000}")
    public void tick() {
        try {
            int filled = poll.pollOnce();
            if (filled > 0) log.info("易保全备案回填：本轮 {} 件已备案", filled);
        } catch (RuntimeException e) {
            log.error("易保全备案轮询异常（下次重试）", e);
        }
    }
}
