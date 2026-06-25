package com.youzheng.huicui.common;

/** 分页元信息，对齐契约 *Page schema 的 meta 子对象 {page,size,total}。 */
public record PageMeta(int page, int size, long total) {}
