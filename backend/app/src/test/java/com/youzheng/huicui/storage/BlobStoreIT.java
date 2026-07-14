package com.youzheng.huicui.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对象存储（S3 协议）。跑在真 MinIO 上 —— AWS S3 / 阿里云 OSS / MinIO 走同一套 API。
 *
 * <p>要守住的是<b>「双读」</b>那条命：新录音进对象存储、存量录音留在 bytea，
 * 读的时候先看 key、没有再回落。**顺序反了，或者 get 不到时抛异常而不是返回 null，
 * 都会把已有的历史录音读成"没有录音"** —— 而那是不可逆的用户感知损失（他会以为这通电话没录上）。
 *
 * <p>跑法：{@code HUICUI_IT_S3_ENDPOINT=http://localhost:9100 mvn verify}
 */
class BlobStoreIT {

    private static final String ENDPOINT = System.getenv().getOrDefault("HUICUI_IT_S3_ENDPOINT", "");
    private static S3BlobStore store;

    @BeforeAll
    static void setup() {
        assumeTrue(!ENDPOINT.isBlank(), "未设置 HUICUI_IT_S3_ENDPOINT，跳过对象存储 IT");
        store = new S3BlobStore(ENDPOINT, "us-east-1", "minioadmin", "minioadmin123", "huicui", true);
    }

    @Test
    void 存进去取出来_字节完全一致() {
        byte[] audio = new byte[64 * 1024];          // 64KB「录音」
        for (int i = 0; i < audio.length; i++) audio[i] = (byte) (i % 251);

        String key = "recordings/test/" + System.nanoTime();
        store.put(key, audio, "audio/wav");

        assertThat(store.get(key))
                .as("取回的音频必须与存进去的逐字节相同（录音是要拿去举证的，错一个字节都不行）")
                .isEqualTo(audio);
    }

    /**
     * <b>取不到时必须返回 null，不能抛异常。</b>
     * 双读靠它回落到 bytea —— 一抛异常，所有存量录音（audio_key 为空、字节在库里）就全都读不出来了。
     */
    @Test
    void 对象不存在时返回null而不是抛异常() {
        assertThat(store.get("recordings/根本不存在的key/" + System.nanoTime()))
                .as("对象不存在必须返回 null —— 双读要靠它回落到 bytea")
                .isNull();
    }

    @Test
    void 删除后就真的没了() {
        String key = "attachments/test/" + System.nanoTime();
        store.put(key, "业主张三 13900001111 翠湖一期3-1-502".getBytes(), "text/plain");
        assertThat(store.get(key)).isNotNull();

        store.delete(key);

        assertThat(store.get(key))
                .as("留存清理必须真的把对象从桶里删掉——库里抹了 key 而对象还在，等于没删")
                .isNull();
    }

    @Test
    void 大对象也扛得住() {
        byte[] big = new byte[5 * 1024 * 1024];      // 5MB：一通几分钟的真实通话录音量级
        String key = "recordings/big/" + System.nanoTime();
        store.put(key, big, "audio/wav");
        assertThat(store.get(key)).hasSize(big.length);
        store.delete(key);
    }
}
