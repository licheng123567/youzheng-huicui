package com.youzheng.huicui.storage;

/**
 * 二进制大对象存储（录音音频、送达附件）。
 *
 * <p><b>为什么要有这层</b>：录音与附件此前直接以 {@code bytea} 存在 Postgres 里。
 * 录音是 8k 采样的通话音频，一个批次几千通电话就能把库撑到几十 GB，而它拖累的是**一整串东西**：
 * <ul>
 *   <li><b>备份</b>：pg_dump 体积随录音线性增长，备份窗口和恢复时长一起恶化
 *       —— 而这个系统的备份还要加密、要传异地；</li>
 *   <li><b>连接池</b>：一次回听要把整段音频读进 JVM 再吐出去，大对象长时间占住一条数据库连接
 *       （池子只有 10 条）；</li>
 *   <li><b>迁移</b>：以后想换库、想做只读副本，都得先把这几十 GB 挪走。</li>
 * </ul>
 *
 * <p><b>迁移策略是「双读」，不是「一刀切」</b>：老数据在 bytea 里，新数据进对象存储。
 * 读的时候先看有没有 object key，没有就回落 bytea。
 * 这样开关一开，历史录音照样听得到 —— 一次重构把已有录音读丢，是绝不能接受的。
 */
public interface BlobStore {

    /** 写入并返回 object key（调用方把它存进业务表）。 */
    String put(String key, byte[] bytes, String contentType);

    /** 读取；对象不存在返回 null（而不是抛异常 —— 双读回落要靠它）。 */
    byte[] get(String key);

    /** 删除（留存清理到期时要真删对象，不能只把库里的 key 抹掉）。 */
    void delete(String key);

    /** 这个实现是不是「真的对象存储」（false = 还在 PG bytea 里）。仅供启动日志与运维自检。 */
    boolean isExternal();
}
