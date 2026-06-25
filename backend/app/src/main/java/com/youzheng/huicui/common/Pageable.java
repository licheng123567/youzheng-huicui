package com.youzheng.huicui.common;

/**
 * 分页入参规整助手，对齐契约 parameters：
 *   Page minimum=1 default=1；Size minimum=1 max=200 default=20。
 * 不信任客户端越界值：page<1→1，size<1→20，size>200→200。
 * offset 供 LIMIT size OFFSET offset 直读。
 */
public final class Pageable {
    public final int page;
    public final int size;
    public final long offset;

    private Pageable(int page, int size) {
        this.page = page;
        this.size = size;
        this.offset = (page - 1L) * size;
    }

    public static Pageable of(Integer page, Integer size) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 20 : Math.min(size, 200);
        return new Pageable(p, s);
    }
}
