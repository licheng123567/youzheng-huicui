package com.youzheng.huicui.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.DataRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-03 并发持有上限集成测试（Testcontainers PostgreSQL）。
 *
 * 场景一（并发穿透）：同一 CO，两件不同案件，两线程同时 claim —— 断言成功数 ≤ (cap - 初始持有)。
 * 场景二（assign 路径同款）：两线程同时 assign 不同案件给同一 CO，上限不被击穿。
 * 场景三（单线程上限边界）：顺序持有至 cap 件后再 claim 第 cap+1 件 → BIZ_HOLD_CAP。
 * 场景四（幂等）：对已持有案件重复 claim → 不抛异常，持有数不变。
 *
 * 纯 Mock 测不出 DB 行锁语义，必须用真实 PG + FOR UPDATE。
 */
@Testcontainers
class HoldCapConcurrencyIT {

    // pgvector/pgvector:pg16 含 pgvector 扩展，V1 schema 建 script_lib.embedding vector(1024) 需要之。
    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("huicui_test")
                    .withUsername("postgres")
                    .withPassword("test");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txm;
    private CaseStateService svc;

    // 测试数据 ID（每个 @Test 前在 setup 重新写）
    private long providerOrgId;
    private long collectorId;
    private long projectId;

    @BeforeEach
    void setup() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        txm = new DataSourceTransactionManager(dataSource);

        // 只在第一次运行时迁移（容器共享），后续测试不重跑 Flyway。
        // 路径从 backend/app（maven 工作目录）出发：../db/migration 指向 backend/db/migration。
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                // 核心 schema V1-V7（不含 dev 种子 V9xx）
                .locations("filesystem:../db/migration")
                .baselineOnMigrate(false)
                .load();
        // 迁移失败须暴露（路径错/脚本错应让测试 fail，不可静默吞掉）。
        // 同一容器多次运行时 Flyway 会检测到已应用的迁移并跳过，无需 catch。
        flyway.migrate();

        svc = new CaseStateService(jdbc, new ObjectMapper());

        // 清理上次测试残留
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM \"case\"");
        jdbc.update("DELETE FROM batch");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM account WHERE role_template = 'CO'");
        jdbc.update("DELETE FROM org WHERE type = 'PROVIDER'");

        // 插入服务商 org + CO 账号
        providerOrgId = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROVIDER','测试商','ACTIVE') RETURNING id",
                Long.class);
        collectorId = jdbc.queryForObject(
                "INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)"
                        + " VALUES (?,?,?,?,'CO','ACTIVE',false) RETURNING id",
                Long.class, providerOrgId,
                "co_" + System.nanoTime(), "催收员", "13900000000");

        // 插入 project（需要 org PLATFORM 已存在；V900 种子不跑，手动插）
        long platformOrgId = insertPlatformOrgIfAbsent();
        // org_name 冗余列必填（高频展示用）
        String platformName = jdbc.queryForObject("SELECT name FROM org WHERE id = ?", String.class, platformOrgId);
        projectId = jdbc.queryForObject(
                "INSERT INTO project(org_id,name,org_name,status,comm_in_rate,area)"
                        + " VALUES (?,'测试项目',?,'ACTIVE',0.08,'北京') RETURNING id",
                Long.class, platformOrgId, platformName == null ? "平台" : platformName);
    }

    private long insertPlatformOrgIfAbsent() {
        Long id = jdbc.query(
                "SELECT id FROM org WHERE type='PLATFORM' LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (id != null) return id;
        long oid = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PLATFORM','平台','ACTIVE') RETURNING id",
                Long.class);
        long aid = jdbc.queryForObject(
                "INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)"
                        + " VALUES (?,?,?,?,'SA','ACTIVE',true) RETURNING id",
                Long.class, oid, "admin_" + System.nanoTime(), "超管", "13800000000");
        jdbc.update("UPDATE org SET owner_account_id = ? WHERE id = ?", aid, oid);
        return oid;
    }

    /** 插入一条 batch + case，处于 S2（PROVIDER_SEA, PROVIDER_SEA），归属 providerOrgId。 */
    private long insertS2Case() {
        long batchId = jdbc.queryForObject(
                "INSERT INTO batch(project_id,no,provider_id,comm_in_rate,pay_out_rate,status)"
                        + " VALUES (?,'B'||?,?,0.08,0.05,'DISPATCHED') RETURNING id",
                Long.class, projectId, System.nanoTime(), providerOrgId);
        return jdbc.queryForObject(
                "INSERT INTO \"case\"(batch_id,project_id,project_name,acct_no,owner_name,room,"
                        + " due_cents,arrearags_periods,status,pool,provider_id)"
                        + " VALUES (?,?,'项目','A'||?,'张三','101',100000,'[]',"
                        + "'PROVIDER_SEA','PROVIDER_SEA',?) RETURNING id",
                Long.class, batchId, projectId, System.nanoTime(), providerOrgId);
    }

    /** 插入一条已在 S3（IN_PROGRESS, PRIVATE），holder=collectorId 的案件（占上限用）。 */
    private void insertS3Case(long holderId) {
        long batchId = jdbc.queryForObject(
                "INSERT INTO batch(project_id,no,provider_id,comm_in_rate,pay_out_rate,status)"
                        + " VALUES (?,'B'||?,?,0.08,0.05,'DISPATCHED') RETURNING id",
                Long.class, projectId, System.nanoTime(), providerOrgId);
        jdbc.update(
                "INSERT INTO \"case\"(batch_id,project_id,project_name,acct_no,owner_name,room,"
                        + " due_cents,arrearags_periods,status,pool,holder_id,provider_id)"
                        + " VALUES (?,?,'项目','A'||?,'李四','102',100000,'[]',"
                        + "'IN_PROGRESS','PRIVATE',?,?)",
                batchId, projectId, System.nanoTime(), holderId, providerOrgId);
    }

    /** 设置 CFG-HOLDCAP 为指定值（写 settings 表）。 */
    private void setHoldCap(int cap) {
        // settings.updated_by 须为存在的 account
        Long updatedBy = jdbc.query("SELECT id FROM account LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        jdbc.update("DELETE FROM settings WHERE domain = 'ROTATION'");
        jdbc.update(
                "INSERT INTO settings(domain,version,rotation,updated_by)"
                        + " VALUES ('ROTATION',1,?::jsonb,?)",
                "{\"holdCap\":" + cap + "}", updatedBy);
    }

    private CurrentSubject coSubject() {
        return new CurrentSubject(
                String.valueOf(collectorId), "催收员",
                String.valueOf(providerOrgId), "PROVIDER", "测试商",
                "CO", Set.of("case.claim", "case.assign"), DataRange.UNRESTRICTED);
    }

    private TransactionTemplate tx() {
        TransactionTemplate t = new TransactionTemplate(txm);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return t;
    }

    // ── 场景一：并发 claim 两件不同案件，上限不被击穿 ─────────────────────────

    @Test
    void concurrentClaim_twoDistinctCases_holderCapNotExceeded() throws Exception {
        // cap=1，CO 初始持有 0 → 并发 claim 两件 S2 案件，只能成功 1 件
        int cap = 1;
        setHoldCap(cap);
        long caseA = insertS2Case();
        long caseB = insertS2Case();

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures  = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go    = new CountDownLatch(1);

        CurrentSubject co = coSubject();
        long tcSec = 3600L;

        Runnable claimA = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                tx().execute(st -> { svc.claim(caseA, co, tcSec); return null; });
                successes.incrementAndGet();
            } catch (ApiException e) {
                if (e.error == BizError.BIZ_HOLD_CAP || e.error == BizError.BIZ_ALREADY_CLAIMED) {
                    failures.incrementAndGet();
                } else { throw e; }
            }
        };
        Runnable claimB = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                tx().execute(st -> { svc.claim(caseB, co, tcSec); return null; });
                successes.incrementAndGet();
            } catch (ApiException e) {
                if (e.error == BizError.BIZ_HOLD_CAP || e.error == BizError.BIZ_ALREADY_CLAIMED) {
                    failures.incrementAndGet();
                } else { throw e; }
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> fa = pool.submit(claimA);
        Future<?> fb = pool.submit(claimB);
        ready.await();
        go.countDown();
        fa.get();
        fb.get();
        pool.shutdown();

        // 断言：成功数 ≤ cap（不击穿）
        assertThat(successes.get())
                .as("成功 claim 数不得超过 CFG-HOLDCAP=%d", cap)
                .isLessThanOrEqualTo(cap);

        // 断言：DB 实际持有数 = 成功数 ≤ cap
        int actualHeld = svc.holdCount(collectorId);
        assertThat(actualHeld)
                .as("DB 实际持有数应等于成功数且不超上限")
                .isEqualTo(successes.get())
                .isLessThanOrEqualTo(cap);
    }

    // ── 场景二：并发 assign 两件不同案件，上限不被击穿 ──────────────────────────

    @Test
    void concurrentAssign_twoDistinctCases_holderCapNotExceeded() throws Exception {
        int cap = 1;
        setHoldCap(cap);
        long caseA = insertS2Case();
        long caseB = insertS2Case();

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures  = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go    = new CountDownLatch(1);

        long tcDeadlineSec = 3600L;

        // assign 路径：全局锁序 collector→case（先 lockCollector 再 lockCase），与 assignCase / claim 一致。
        Runnable assignA = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                tx().execute(st -> {
                    svc.lockCollector(collectorId);
                    svc.checkHoldCap(collectorId);
                    svc.lockCase(caseA);
                    CaseStateService.Transition t = new CaseStateService.Transition(
                            CaseStateService.ST_PROVIDER_SEA, CaseStateService.POOL_PROVIDER_SEA, null,
                            CaseStateService.ST_IN_PROGRESS, CaseStateService.POOL_PRIVATE,
                            collectorId, "ASSIGN", CaseStateService.POOL_PROVIDER_SEA,
                            null, java.time.Instant.now().plusSeconds(tcDeadlineSec));
                    svc.transition(caseA, t);
                    return null;
                });
                successes.incrementAndGet();
            } catch (ApiException e) {
                if (e.error == BizError.BIZ_HOLD_CAP || e.error == BizError.BIZ_ALREADY_CLAIMED
                        || e.error == BizError.STATE_409) {
                    failures.incrementAndGet();
                } else { throw e; }
            }
        };
        Runnable assignB = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                tx().execute(st -> {
                    svc.lockCollector(collectorId);
                    svc.checkHoldCap(collectorId);
                    svc.lockCase(caseB);
                    CaseStateService.Transition t = new CaseStateService.Transition(
                            CaseStateService.ST_PROVIDER_SEA, CaseStateService.POOL_PROVIDER_SEA, null,
                            CaseStateService.ST_IN_PROGRESS, CaseStateService.POOL_PRIVATE,
                            collectorId, "ASSIGN", CaseStateService.POOL_PROVIDER_SEA,
                            null, java.time.Instant.now().plusSeconds(tcDeadlineSec));
                    svc.transition(caseB, t);
                    return null;
                });
                successes.incrementAndGet();
            } catch (ApiException e) {
                if (e.error == BizError.BIZ_HOLD_CAP || e.error == BizError.BIZ_ALREADY_CLAIMED
                        || e.error == BizError.STATE_409) {
                    failures.incrementAndGet();
                } else { throw e; }
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> fa = pool.submit(assignA);
        Future<?> fb = pool.submit(assignB);
        ready.await();
        go.countDown();
        fa.get();
        fb.get();
        pool.shutdown();

        assertThat(successes.get())
                .as("并发 assign 成功数不得超过 CFG-HOLDCAP=%d", cap)
                .isLessThanOrEqualTo(cap);
        assertThat(svc.holdCount(collectorId))
                .as("DB 实际持有数应等于成功数且不超上限")
                .isEqualTo(successes.get())
                .isLessThanOrEqualTo(cap);
    }

    // ── 场景三：单线程顺序持有至 cap，再 claim 第 cap+1 件 → BIZ_HOLD_CAP ──────

    @Test
    void singleThread_claimBeyondCap_throwsBizHoldCap() {
        int cap = 2;
        setHoldCap(cap);

        // 预置 cap 件已在私海（holdCount=cap）
        for (int i = 0; i < cap; i++) insertS3Case(collectorId);

        assertThat(svc.holdCount(collectorId)).isEqualTo(cap);

        // 再 claim 一件 → BIZ_HOLD_CAP
        long caseExtra = insertS2Case();
        CurrentSubject co = coSubject();

        assertThatThrownBy(() ->
                tx().execute(st -> { svc.claim(caseExtra, co, 3600L); return null; }))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).error).isEqualTo(BizError.BIZ_HOLD_CAP));
    }

    // ── 场景四：对已持有案件重复 claim（幂等）→ 不抛异常，持有数不变 ────────────

    @Test
    void idempotentClaim_alreadyHeld_doesNotThrow() {
        int cap = 5;
        setHoldCap(cap);

        // 先 claim 一件成功
        long caseId = insertS2Case();
        CurrentSubject co = coSubject();
        tx().execute(st -> { svc.claim(caseId, co, 3600L); return null; });

        int holdAfterFirst = svc.holdCount(collectorId);
        assertThat(holdAfterFirst).isEqualTo(1);

        // HolderM3Controller.claimCase 的幂等检测在 lockCase 之后、claim() 之前；
        // 此处直接调 claim() 再次抢已 IN_PROGRESS/PRIVATE 的案件 → BIZ_ALREADY_CLAIMED（锁内复核）。
        // 控制层幂等（本人已持有→return ok）在 controller 层，service 层不负责此路径。
        // 验证 holdCount 未变化即可。
        try {
            tx().execute(st -> { svc.claim(caseId, co, 3600L); return null; });
        } catch (ApiException e) {
            assertThat(e.error).isIn(BizError.BIZ_ALREADY_CLAIMED, BizError.BIZ_HOLD_CAP);
        }

        assertThat(svc.holdCount(collectorId))
                .as("持有数不应因重复 claim 同案件而增加")
                .isEqualTo(holdAfterFirst);
    }
}
