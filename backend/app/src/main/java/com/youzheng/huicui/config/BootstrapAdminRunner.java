package com.youzheng.huicui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次部署的**一次性管理员引导**。
 *
 * <p>为什么必须有这个东西：生产库是干净的（`db/seed` 不在 prod 的 flyway locations，
 * {@link DevSeeder} 是 {@code @Profile("dev")}），迁移跑完 `account` 表**一行都没有**。
 * 而系统没有公开注册，`/auth/login` 要求账号已存在，`/auth/setup-password` 要求一张
 * 只能由**已登录**成员管理端点签发的一次性票据 —— 死锁。
 * 结果就是：首次部署迁移全绿、健康检查全绿，然后**没有任何人能登录**，
 * 而部署文档里也没有任何一步告诉运维该怎么办。
 *
 * <p>设计上刻意收紧到「只能用一次、且不留长期凭据」：
 * <ul>
 *   <li><b>只在库里一个账号都没有时才动手</b>。已有账号 → 直接跳过。所以它不可能覆盖
 *       线上账号，也不可能被拿来重置别人的口令；重复启动是安全的（幂等）。</li>
 *   <li><b>不给默认口令</b>。没配环境变量 → 什么也不做（保持空库）。这也让
 *       CI 的 {@code deploy-shape} 断言「生产库 account = 0」继续成立。</li>
 *   <li><b>口令强度不足直接拒绝启动</b>，而不是"警告一下然后照建"。引导口令是这套系统的
 *       第一把钥匙，弱口令等于把门开着。</li>
 *   <li><b>建出来的账号 {@code must_change_password = TRUE}</b>。环境变量里的口令只是
 *       一张一次性门票：首次登录后端强制改密（见 JwtAuthFilter 的 must-change 白名单），
 *       改完这个口令就作废了 —— 它不会变成一个长期躺在 .env 和 shell history 里的管理员密码。</li>
 * </ul>
 *
 * <p>用法（只在第一次部署时把这三个变量填进 .env，起来之后就可以删掉）：
 * <pre>
 *   HUICUI_BOOTSTRAP_ADMIN_USERNAME=admin
 *   HUICUI_BOOTSTRAP_ADMIN_PASSWORD=&lt;openssl rand -base64 18&gt;
 *   HUICUI_BOOTSTRAP_ADMIN_PHONE=13800000000
 * </pre>
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    /** 引导口令最短长度。这是系统的第一把钥匙，不接受 8 位那种。 */
    static final int MIN_PASSWORD_LENGTH = 12;

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    private final String username;
    private final String password;
    private final String name;
    private final String phone;
    private final String orgName;

    public BootstrapAdminRunner(
            JdbcTemplate jdbc,
            @Value("${huicui.bootstrap.admin-username:}") String username,
            @Value("${huicui.bootstrap.admin-password:}") String password,
            @Value("${huicui.bootstrap.admin-name:平台超管}") String name,
            @Value("${huicui.bootstrap.admin-phone:}") String phone,
            @Value("${huicui.bootstrap.org-name:有证平台}") String orgName) {
        this.jdbc = jdbc;
        this.username = username;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.orgName = orgName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isBlank(username) && isBlank(password)) {
            // 没配就什么都不做——空库保持空库。这条路径必须存在：CI 的部署形态检查
            // 正是在「不配引导变量」的前提下断言 prod 库 account = 0。
            return;
        }

        Integer accounts = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
        if (accounts != null && accounts > 0) {
            // 已经有账号了：绝不触碰。引导只负责「从无到有」，不负责「重置」。
            log.info("[Bootstrap] 库中已有 {} 个账号，跳过初始管理员引导（可以从 .env 里删掉 HUICUI_BOOTSTRAP_ADMIN_* 了）。", accounts);
            return;
        }

        // 到这里说明：运维明确要求引导，且库是空的。此时任何一个参数不合格都必须**拒绝启动** ——
        // 建一个弱口令的超管，比不建更糟。
        if (isBlank(username)) fail("HUICUI_BOOTSTRAP_ADMIN_USERNAME 为空。");
        if (isBlank(password)) fail("HUICUI_BOOTSTRAP_ADMIN_PASSWORD 为空。");
        if (password.length() < MIN_PASSWORD_LENGTH) {
            fail("HUICUI_BOOTSTRAP_ADMIN_PASSWORD 太短（要求 ≥" + MIN_PASSWORD_LENGTH
                    + " 位，当前 " + password.length() + " 位）。生成：openssl rand -base64 18");
        }
        if (isBlank(phone)) fail("HUICUI_BOOTSTRAP_ADMIN_PHONE 为空（登录与短信找回都要用）。");

        Long orgId = jdbc.queryForObject(
                "INSERT INTO org(type, name, status) VALUES ('PLATFORM', ?, 'ACTIVE') RETURNING id",
                Long.class, orgName);

        // must_change_password=TRUE：环境变量里的口令只是一次性门票，登录后立刻被强制改掉。
        Long accountId = jdbc.queryForObject(
                "INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner,"
                        + " password_hash, must_change_password)"
                        + " VALUES (?, ?, ?, ?, 'SA', 'ACTIVE', TRUE, ?, TRUE) RETURNING id",
                Long.class, orgId, username, name, phone, bcrypt.encode(password));

        jdbc.update("UPDATE org SET owner_account_id = ? WHERE id = ?", accountId, orgId);

        log.warn("\n"
                + "==================== 初始管理员已创建 ====================\n"
                + "[Bootstrap] 平台组织「{}」+ 超管账号「{}」（id={}）已建好。\n"
                + "[Bootstrap] 首次登录会被**强制改密**——环境变量里的那个口令改完即作废。\n"
                + "[Bootstrap] 改密后请把 HUICUI_BOOTSTRAP_ADMIN_* 三个变量从 .env 里删掉。\n"
                + "=========================================================", orgName, username, accountId);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static void fail(String msg) {
        throw new IllegalStateException("[Bootstrap] " + msg
                + "\n（初始管理员引导只在空库上执行一次；宁可起不来，也不要建一个弱口令的超管。）");
    }
}
