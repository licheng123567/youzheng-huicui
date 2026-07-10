package com.youzheng.huicui.common;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约里所有 {@code format: date-time} 字段必须是 RFC3339。
 *
 * 这里同时把「旧写法为什么会炸」钉成用例：Android 客户端用
 * {@code OffsetDateTime.parse(s, ISO_OFFSET_DATE_TIME)} 解析，
 * {@code Timestamp#toString()} 的空格格式会直接抛 DateTimeParseException。
 */
class TimestampsTest {

    /** 与 app-android 生成客户端 OffsetDateTimeAdapter 的解析方式完全一致。 */
    private static OffsetDateTime parseLikeAndroidClient(String s) {
        return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @Test
    void timestamptz_输出RFC3339_且客户端解析得动() {
        Timestamp t = Timestamp.from(Instant.parse("2026-07-09T07:17:59.579476Z"));
        String s = Timestamps.iso(t);

        assertEquals("2026-07-09T07:17:59.579476Z", s);
        assertEquals(Instant.parse("2026-07-09T07:17:59.579476Z"), parseLikeAndroidClient(s).toInstant());
    }

    @Test
    void 旧写法会让客户端抛异常_这就是消息中心打不开的原因() {
        Timestamp t = Timestamp.from(Instant.parse("2026-07-09T07:17:59.579476Z"));
        String legacy = String.valueOf((Object) t);   // 旧代码：String.valueOf(rs.getObject(col))

        assertTrue(legacy.contains(" "), "Timestamp#toString 用空格分隔日期与时间：" + legacy);
        assertThrows(DateTimeParseException.class, () -> parseLikeAndroidClient(legacy));
    }

    @Test
    void date_列取UTC零点_东八区渲染仍是同一天() {
        String s = Timestamps.isoFromDate(Date.valueOf("2026-07-16"));
        assertEquals("2026-07-16T00:00:00Z", s);

        // 东八区看到的仍是 7/16（08:00），不会串到前一天
        assertEquals(16, parseLikeAndroidClient(s).atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                .getDayOfMonth());
    }

    @Test
    void null_传递而不是变成字符串null() {
        assertNull(Timestamps.iso((Timestamp) null));
        assertNull(Timestamps.isoFromDate((Date) null));
        // 旧写法会把 NULL 列变成字面量 "null"，然后客户端拿到一个非法时间字符串
        assertEquals("null", String.valueOf((Object) null));
    }

    @Test
    void 整秒时间不丢Z() {
        Timestamp t = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        assertEquals("2026-01-01T00:00:00Z", Timestamps.iso(t));
        parseLikeAndroidClient(Timestamps.iso(t));   // 不抛即通过
    }
}
