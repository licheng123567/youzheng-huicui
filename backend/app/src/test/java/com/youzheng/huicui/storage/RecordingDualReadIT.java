package com.youzheng.huicui.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.dispatch.RecordingService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>切到对象存储之后，存量录音还听不听得到。</b>
 *
 * <p>这是整个对象存储改造里最容易翻车、也最不可接受的一处：
 * 历史录音的字节在 {@code audio_bytes} 里、{@code audio_key} 是空的。
 * 只要读路径写成「一律去对象存储取」，这些录音就会全部变成「没有录音」——
 * 而催收员会以为这通电话本来就没录上，投诉来了也拿不出证据。**一次重构把已有录音读丢，绝不能接受。**
 *
 * <p>所以这条用例是**双向**的：同一个开着对象存储的实例，
 * 既要能读新录音（对象存储），也要能读老录音（bytea 回落）。
 */
class RecordingDualReadIT {

    private static final String JDBC = System.getenv().getOrDefault("HUICUI_IT_JDBC_URL", "");
    private static final String S3 = System.getenv().getOrDefault("HUICUI_IT_S3_ENDPOINT", "");

    private static JdbcTemplate jdbc;
    private static RecordingService svc;
    private static long caseId;

    @BeforeAll
    static void setup() {
        assumeTrue(!JDBC.isBlank() && !S3.isBlank(), "缺 HUICUI_IT_JDBC_URL 或 HUICUI_IT_S3_ENDPOINT，跳过双读 IT");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(JDBC);
        pg.setUser(System.getenv().getOrDefault("HUICUI_IT_JDBC_USER", "huicui"));
        pg.setPassword(System.getenv().getOrDefault("HUICUI_IT_JDBC_PASSWORD", "Str0ngProdPw"));
        DataSource ds = pg;
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();

        // **开着对象存储**的 RecordingService —— 这正是切换之后的生产形态
        BlobStore blobs = new S3BlobStore(S3, "us-east-1", "minioadmin", "minioadmin123", "huicui", true);
        svc = new RecordingService(jdbc, new ObjectMapper(), blobs);

        long orgId = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROVIDER','测试商','ACTIVE') RETURNING id", Long.class);
        long acc = jdbc.queryForObject(
                "INSERT INTO account(org_id,username,name,phone,role_template,status,is_owner)"
                        + " VALUES (?,?,'催收员','13900000008','CO','ACTIVE',false) RETURNING id",
                Long.class, orgId, "co_dual_" + System.nanoTime());
        long propOrg = jdbc.queryForObject(
                "INSERT INTO org(type,name,status) VALUES ('PROPERTY','物业','ACTIVE') RETURNING id", Long.class);
        String pname = "小区" + System.nanoTime();
        long projectId = jdbc.queryForObject(
                "INSERT INTO project(org_id,name,org_name,status,area,comm_in_rate)"
                        + " VALUES (?,?,'物业','ACTIVE','区',0.30) RETURNING id",
                Long.class, propOrg, pname);
        long batchId = jdbc.queryForObject(
                "INSERT INTO batch(project_id,no,status,comm_in_rate) VALUES (?,?,'DISPATCHED',0.30) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime());
        caseId = jdbc.queryForObject(
                "INSERT INTO \"case\"(batch_id,project_id,project_name,acct_no,owner_name,room,due_cents,status,pool,holder_id)"
                        + " VALUES (?,?,?,?,'张三','1-101',500000,'IN_PROGRESS','PROVIDER_SEA',?) RETURNING id",
                Long.class, batchId, projectId, pname, "A-" + System.nanoTime(), acc);
    }

    /** 新录音：字节进桶，库里只留 key，读得回来。 */
    @Test
    void 新录音走对象存储_库里不再存字节() {
        long recId = insertRecording();
        byte[] audio = "新录音的音频字节".getBytes();

        svc.storeAudio(recId, audio, "audio/wav");

        // 库里应当**没有**字节了（这正是省下 dump 体积的地方）
        byte[] inDb = jdbc.query("SELECT audio_bytes FROM call_recording WHERE id=?",
                rs -> rs.next() ? rs.getBytes(1) : null, recId);
        assertThat(inDb).as("启用对象存储后，字节不该再往 PG 里塞").isNull();

        String key = jdbc.queryForObject("SELECT audio_key FROM call_recording WHERE id=?", String.class, recId);
        assertThat(key).as("库里应留下 object key").isNotBlank();

        Object[] loaded = svc.loadAudio(recId);
        assertThat(loaded).isNotNull();
        assertThat((byte[]) loaded[0]).as("新录音必须能从对象存储读回来").isEqualTo(audio);
    }

    /**
     * <b>存量录音（字节在 bytea、key 为空）在开着对象存储的实例上照样能听。</b>
     * 这条挂了，就意味着切换当天，历史上所有录音一起变成「没有录音」。
     */
    @Test
    void 存量bytea录音_在开着对象存储时仍可回放() {
        long recId = insertRecording();
        byte[] legacy = "这是切换前就存在库里的老录音".getBytes();
        // 模拟历史数据：直接写 bytea，audio_key 保持 NULL
        jdbc.update("UPDATE call_recording SET audio_bytes = ?, audio_content_type = 'audio/wav',"
                + " audio_key = NULL WHERE id = ?", legacy, recId);

        Object[] loaded = svc.loadAudio(recId);

        assertThat(loaded).as("存量录音不能因为切了对象存储就读不出来").isNotNull();
        assertThat((byte[]) loaded[0])
                .as("必须回落到 bytea 把老录音原样读回 —— 否则切换当天历史录音全部消失")
                .isEqualTo(legacy);
    }

    private long insertRecording() {
        return jdbc.queryForObject(
                "INSERT INTO call_recording(case_id,collector_id,source,status,duration_sec)"
                        + " VALUES (?, (SELECT holder_id FROM \"case\" WHERE id=?), 'APP_AUTO','READY',60)"
                        + " RETURNING id",
                Long.class, caseId, caseId);
    }
}
