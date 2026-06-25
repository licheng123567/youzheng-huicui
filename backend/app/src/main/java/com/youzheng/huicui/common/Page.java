package com.youzheng.huicui.common;

import java.util.List;

/**
 * 分页包装，对齐契约所有 *Page schema 形状 {items:[...], meta:{page,size,total}}。
 * 查询范式：先 SELECT count(*) 得 total，再 SELECT ... LIMIT size OFFSET offset 得 items，
 * 同一 WHERE 片段（含 DataScope.Fragment.sql/params）两处复用。
 */
public record Page<T>(List<T> items, PageMeta meta) {
    public static <T> Page<T> of(List<T> items, int page, int size, long total) {
        return new Page<>(items, new PageMeta(page, size, total));
    }

    /** 用 Pageable 入参直接组装，免去手抄 page/size。 */
    public static <T> Page<T> of(List<T> items, Pageable p, long total) {
        return new Page<>(items, new PageMeta(p.page, p.size, total));
    }
}
