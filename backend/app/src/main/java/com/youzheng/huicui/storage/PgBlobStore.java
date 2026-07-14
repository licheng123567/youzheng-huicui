package com.youzheng.huicui.storage;

/**
 * 「不启用对象存储」时的占位实现。
 *
 * <p>它**不真的存东西** —— 字节仍然由各业务表的 {@code bytea} 列自己存（保持原行为不变）。
 * 存在的意义是让调用方永远拿到一个非空的 {@link BlobStore}，
 * 靠 {@link #isExternal()} 判断该走对象存储还是走老路，而不是到处写 {@code if (blobStore != null)}。
 */
public class PgBlobStore implements BlobStore {

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        throw new UnsupportedOperationException(
                "未启用对象存储时不应调用 BlobStore.put —— 调用方须先判 isExternal()");
    }

    @Override
    public byte[] get(String key) {
        return null;   // 没有对象存储 → 一律回落 bytea
    }

    @Override
    public void delete(String key) {
        // no-op：没有对象要删
    }

    @Override
    public boolean isExternal() {
        return false;
    }
}
