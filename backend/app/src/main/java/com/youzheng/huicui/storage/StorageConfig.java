package com.youzheng.huicui.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配。
 *
 * <p><b>默认仍是 PG bytea（{@code type=pg}）</b> —— 不配置就一切照旧，行为零变化。
 * 这是刻意的：把「换存储」和「升级版本」这两件事解耦，谁都不想在一次例行升级里
 * 顺手把录音的存取路径换掉。想切就显式设 {@code HUICUI_STORAGE_TYPE=s3}。
 *
 * <p>切换之后**老录音照样能听**：读路径是双读（先看 object key，没有就回落 bytea），
 * 见 {@link BlobStore} 的注释。
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public BlobStore blobStore(
            @Value("${huicui.storage.type:pg}") String type,
            @Value("${huicui.storage.s3.endpoint:}") String endpoint,
            @Value("${huicui.storage.s3.region:cn-north-1}") String region,
            @Value("${huicui.storage.s3.access-key:}") String accessKey,
            @Value("${huicui.storage.s3.secret-key:}") String secretKey,
            @Value("${huicui.storage.s3.bucket:}") String bucket,
            @Value("${huicui.storage.s3.path-style:true}") boolean pathStyle) {

        if (!"s3".equalsIgnoreCase(type)) {
            log.warn("[Storage] 录音与附件仍存在 PG bytea 里（HUICUI_STORAGE_TYPE 未设为 s3）。"
                    + "dump 体积会随录音线性增长，备份与恢复时长都受它拖累。");
            return new PgBlobStore();
        }

        // 开了 s3 却没配全 —— **拒绝启动**，而不是悄悄回落到 bytea。
        // 「以为切了对象存储、其实还在往库里塞」是这类开关最典型的失效方式：
        // 你会在几个月后、库涨到几十 GB 的时候才发现。
        if (accessKey.isBlank() || secretKey.isBlank() || bucket.isBlank()) {
            throw new IllegalStateException(
                    "[Storage] HUICUI_STORAGE_TYPE=s3 但缺 access-key / secret-key / bucket。"
                            + "宁可起不来，也不要「以为切了对象存储、其实还在往 PG 里塞录音」。");
        }
        return new S3BlobStore(endpoint, region, accessKey, secretKey, bucket, pathStyle);
    }
}
