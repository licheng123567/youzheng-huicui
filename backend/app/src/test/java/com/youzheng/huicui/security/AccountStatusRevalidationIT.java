package com.youzheng.huicui.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 停权 / 离职 → <b>当场登不进、也用不了</b>。
 *
 * <p>此前 {@link JwtAuthFilter} 解析完 JWT 只查 {@code must_change_password}，
 * <b>从不查 {@code account.status}</b>，而 JWT 默认 24 小时有效。于是被停权、被开除的员工，
 * 在最长 24 小时内**照样能继续拉业主的姓名、电话、住址、通话录音** ——
 * 停权只在"下次登录"时才生效，可他根本不需要再登录一次。
 *
 * <p>这条必须有测试守着：它是一段"平时看不出来"的鉴权逻辑，一旦有人为了省一次查询把它删掉，
 * 没有测试就没有任何东西会喊。
 */
class AccountStatusRevalidationIT {

    private static final String URL = System.getenv().getOrDefault("HUICUI_IT_JDBC_URL", "");
    private static final String USER = System.getenv().getOrDefault("HUICUI_IT_JDBC_USER", "huicui");
    private static final String PASS = System.getenv().getOrDefault("HUICUI_IT_JDBC_PASSWORD", "Str0ngProdPw");

    private static JdbcTemplate jdbc;
    private static JwtService jwt;

    @BeforeAll
    static void setup() {
        assumeTrue(!URL.isBlank(), "未设置 HUICUI_IT_JDBC_URL，跳过账号状态复核 IT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(URL);
        pg.setUser(USER);
        pg.setPassword(PASS);
        DataSource ds = pg;
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
        jwt = new JwtService("test-secret-至少32字节用于HS256签名0123456789abcdef", 86400);
    }

    @Test
    void 账号被停权后_同一个未过期的令牌立刻失效() throws Exception {
        long orgId = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROVIDER','测试商','ACTIVE') RETURNING id", Long.class);
        long accountId = jdbc.queryForObject(
                "INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)"
                        + " VALUES (?,?,'催收员','13900000009','CO','ACTIVE',false) RETURNING id",
                Long.class, orgId, "co_rev_" + System.nanoTime());

        String token = jwt.issue(new CurrentSubject(
                String.valueOf(accountId), "催收员", String.valueOf(orgId), "PROVIDER", "测试商", "CO",
                java.util.Set.of("case.call"), DataRange.UNRESTRICTED));

        JwtAuthFilter filter = new JwtAuthFilter(jwt, jdbc);

        // ① 在职：放行
        assertThat(statusOf(filter, token)).as("在职账号应放行").isEqualTo(200);

        // ② 停权（离职同理：status → DISABLED）
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", accountId);

        // ③ **同一个令牌**（未过期）：必须当场 401，而不是等 24 小时过期
        assertThat(statusOf(filter, token))
                .as("停权后，同一个未过期的令牌必须立刻失效（否则被开除的员工还能拉 24 小时业主隐私）")
                .isEqualTo(401);
    }

    /** 把请求过一遍 filter，返回响应码（200 = 放行到了下游）。 */
    private int statusOf(JwtAuthFilter filter, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/cases");
        req.setServletPath("/cases");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }
}
