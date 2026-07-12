package com.youzheng.huicui.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 转写任务轮询（v1.24.0）。百炼录音文件识别是异步的：提交拿 task_id，之后必须有人来问「好了没」。
 * 与存证回填轮询（EvidencePollScheduler）同一范式。
 *
 * <p>未接入 AI（无 key/未启用）时 pollOnce() 直接返回，等于不存在——不会空转打日志。
 * test profile 不启用，避免 e2e/CI 里定时器跟测试抢数据。
 */
@Component
@Profile("!test")
public class AiPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiPollScheduler.class);

    private final AiPipelineService pipeline;

    public AiPollScheduler(AiPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(fixedDelayString = "${huicui.asr.poll.fixed-delay-ms:20000}",
            initialDelayString = "${huicui.asr.poll.initial-delay-ms:15000}")
    public void poll() {
        try {
            pipeline.pollOnce();
        } catch (Exception e) {
            log.warn("转写轮询异常（下轮重试）: {}", e.toString());
        }
    }
}
