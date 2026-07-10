package com.youzheng.huicui.common;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时间戳统一出口：契约里所有 {@code format: date-time} 字段都必须是 RFC3339（ISO-8601 带 Z）。
 *
 * <p><b>为什么需要这个类</b>：{@code String.valueOf(rs.getObject("created_at"))} 拿到的是
 * {@code java.sql.Timestamp#toString()}，输出形如 {@code "2026-07-09 07:17:59.579476"}
 * —— 用空格分隔、没有时区。它长得像时间，能过肉眼、能过 JSON，但**任何按 RFC3339 解析的客户端都会抛异常**。
 * Android 客户端的 {@code OffsetDateTime.parse(..., ISO_OFFSET_DATE_TIME)} 就是在这里炸的：
 * 整个 {@code /notifications} 响应反序列化失败，消息中心永远打不开。
 *
 * <p>该缺陷在浏览器端不显形（JS 的 {@code new Date(str)} 宽容得多），所以一直没人发现。
 * 新代码一律用本类，不要再写 {@code String.valueOf(rs.getObject(...))}。
 */
public final class Timestamps {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private Timestamps() {}

    /** {@code timestamptz} → {@code "2026-07-09T07:17:59.579476Z"}；null 传递。 */
    public static String iso(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    /**
     * {@code date} → {@code "2026-07-16T00:00:00Z"}；null 传递。
     *
     * <p>契约把这些字段声明为 {@code date-time}，而库里是纯 {@code date}（如承诺分期的应还日）。
     * 取 UTC 零点：东八区渲染出来仍是同一天（08:00），不会串日期。
     */
    public static String isoFromDate(Date d) {
        return d == null ? null : ISO.format(d.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    /** ResultSet 便捷读法（列可为 NULL）。 */
    public static String iso(ResultSet rs, String column) throws SQLException {
        return iso(rs.getTimestamp(column));
    }

    public static String isoFromDate(ResultSet rs, String column) throws SQLException {
        return isoFromDate(rs.getDate(column));
    }
}
