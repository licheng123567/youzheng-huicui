package com.youzheng.huicui.common;

import com.youzheng.huicui.security.CurrentSubject;

/**
 * x-data-scope 数据范围助手（骨架）：按当前主体生成 SQL 过滤片段，服务端强制裁剪。
 * 生产：作为 MyBatis 拦截器自动注入（RuoYi 底座），此处给纯 JdbcTemplate 的等价范式。
 *
 * 词表：platform(全量) / own-org(本组织) / range(SE 区域·物业·服务商三维) /
 *       case-holder(本案持有催收员) / case-actor(持有 CO + 关联 PL/PC + SA 代) / public。
 */
public final class DataScope {
    private DataScope() {}

    /** own-org：平台主体不限，其余限本组织。返回追加到 WHERE 的片段（含前导 AND）与参数。 */
    public static Fragment ownOrg(CurrentSubject s, String orgIdColumn) {
        if (s.isPlatform()) return Fragment.NONE;            // 平台见全量
        return new Fragment(" AND " + orgIdColumn + " = ?", new Object[]{ Long.valueOf(s.orgId()) });
    }

    public record Fragment(String sql, Object[] params) {
        public static final Fragment NONE = new Fragment("", new Object[0]);
    }
}
