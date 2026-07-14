package com.youzheng.huicui.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据留存与去标识化（《个人信息保护法》：保存期限为实现目的所必需的最短时间）。
 *
 * <p>此前录音、转写、身份证号、手机号、住址**进库即永久**，既无期限也无删除路径。
 *
 * <h3>分级（为什么不是一刀切 60 天）</h3>
 * <ul>
 *   <li><b>业主 PII</b>（姓名/手机/身份证/通讯地址/房号）→ 结案后 <b>60 天去标识化</b>。</li>
 *   <li><b>通话录音 + 转写</b> → 结案后 <b>6 个月删除</b>。</li>
 *   <li><b>回款 / 佣金 / 支付申请单</b> → <b>不动</b>（会计凭证，法定保管期远长于此）。</li>
 * </ul>
 *
 * <p><b>录音无法"去标识化"，只能删除</b>：声音本身就是个人信息（声纹属敏感个人信息），
 * 转写文本里满是姓名、住址、家庭状况。把姓名字段抹掉而录音还留着，等于什么都没做。
 *
 * <p><b>录音为什么是 6 个月而不是 60 天</b>：催收业务最大的法律风险是业主投诉「暴力催收/骚扰」，
 * 那时通话录音是<b>平台自证清白的唯一证据</b>。60 天就删，等投诉来了手里什么都没有。
 *
 * <h3>房号：抹掉，小区保留</h3>
 * 「翠湖一期 3-1-502 + 欠费 5000」——就算姓名和电话都抹了，拿着房号照样能找到人，
 * 那不叫匿名。而报表的聚合维度是**小区/项目**，不需要房号。故抹房号、留小区：
 * 隐私去掉了，报表不受影响。
 *
 * <h3>手机号为什么直接置空而不是哈希</h3>
 * 11 位手机号的取值空间几秒钟就能穷举反查，<b>哈希 ≠ 匿名</b>，处理后仍属个人信息、仍受个保法管辖。
 * 只有置空（或删除整行联系人）才是不可逆的。
 *
 * <h3>已知残留（诚实交代，不假装做到了）</h3>
 * <ul>
 *   <li><b>自由文本</b>：跟进备注、减免原因、工单说明里，人是会手写业主姓名和电话的。
 *       定时任务能抹结构化字段，抹不干净这些。产品已确认接受这部分残留。</li>
 *   <li><b>历史备份</b>：主库去标识化之后，保留期内（默认 14 天）的备份里仍是原始 PII。
 *       也就是说"删除"要到备份轮换完才真正生效。</li>
 *   <li><b>送达存证附件</b>：不在本次清理范围。它们是上链存证的法律证据，
 *       删掉字节会破坏可验证性 —— 这是一个需要单独决策的取舍，不该被定时任务顺手做掉。</li>
 * </ul>
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int piiDays;
    private final int recordingDays;

    public RetentionService(
            JdbcTemplate jdbc,
            @Value("${huicui.retention.enabled:true}") boolean enabled,
            @Value("${huicui.retention.pii-days:60}") int piiDays,
            @Value("${huicui.retention.recording-days:180}") int recordingDays) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.piiDays = piiDays;
        this.recordingDays = recordingDays;
    }

    /** 每天 03:30 跑一次（错开 03:00 的备份）。 */
    @Scheduled(cron = "${huicui.retention.cron:0 30 3 * * *}")
    public void run() {
        if (!enabled) return;
        int pii = anonymizeExpiredPii();
        int rec = purgeExpiredRecordings();
        if (pii > 0 || rec > 0) {
            log.warn("[Retention] 去标识化 {} 个案件的业主 PII（{} 天）；删除 {} 个案件的录音与转写（{} 天）",
                    pii, piiDays, rec, recordingDays);
        }
    }

    /**
     * 业主 PII 去标识化。
     *
     * <p>姓名替换成「业主(案件 N)」而不是置空：界面上到处要显示它（时间线、报表、对账），
     * 置空会让一堆地方变成空白或报错，而一个稳定的占位串既不可逆、又不破坏可读性。
     */
    @Transactional
    public int anonymizeExpiredPii() {
        // 联系人整行删除：contact.phone 是 NOT NULL，没法"置空"，而留着号码就是留着 PII。
        jdbc.update(
                "DELETE FROM contact WHERE case_id IN ("
                        + "  SELECT case_id FROM case_retention_anchor"
                        + "  WHERE NOT legal_hold AND anonymized_at IS NULL"
                        + "    AND anchor_at < now() - (? || ' days')::interval)",
                piiDays);

        return jdbc.update(
                "UPDATE \"case\" c SET"
                        // 姓名 → 不可逆占位（保留可读性，不保留身份）
                        + "  owner_name = '业主(案件' || c.id || ')',"
                        // 房号 → 抹掉。「小区 + 房号」足以定位到具体一户，抹了姓名不抹房号等于没匿名。
                        // 小区(project_name)保留：报表按它聚合，且单凭小区定位不到个人。
                        + "  room = '—',"
                        // 诉讼要素里的身份证号与通讯地址（建筑面积/合同编号不是 PII，留着）
                        + "  litigation_fields = (c.litigation_fields - 'idCard' - 'mailingAddr'),"
                        + "  anonymized_at = now(), updated_at = now()"
                        + " FROM case_retention_anchor a"
                        + " WHERE a.case_id = c.id"
                        + "   AND NOT a.legal_hold AND a.anonymized_at IS NULL"
                        + "   AND a.anchor_at < now() - (? || ' days')::interval",
                piiDays);
    }

    /**
     * 通话录音与转写删除。
     *
     * <p>音频字节、转写全文、逐句句段一并删掉 —— 留下任何一样，前面抹的姓名都白抹了。
     * 录音行本身保留（谁在什么时候打过多久的电话，是质检与计费的骨架，且不含 PII）。
     */
    @Transactional
    public int purgeExpiredRecordings() {
        // 句段表：逐句转写文本（PII 密度最高的地方）
        jdbc.update(
                "DELETE FROM transcript_segment WHERE recording_id IN ("
                        + "  SELECT r.id FROM call_recording r JOIN case_retention_anchor a ON a.case_id = r.case_id"
                        + "  WHERE NOT a.legal_hold AND a.recordings_purged_at IS NULL"
                        + "    AND a.anchor_at < now() - (? || ' days')::interval)",
                recordingDays);

        jdbc.update(
                "UPDATE call_recording r SET audio_bytes = NULL, transcript = NULL, updated_at = now()"
                        + " FROM case_retention_anchor a"
                        + " WHERE a.case_id = r.case_id"
                        + "   AND NOT a.legal_hold AND a.recordings_purged_at IS NULL"
                        + "   AND a.anchor_at < now() - (? || ' days')::interval",
                recordingDays);

        return jdbc.update(
                "UPDATE \"case\" c SET recordings_purged_at = now(), updated_at = now()"
                        + " FROM case_retention_anchor a"
                        + " WHERE a.case_id = c.id"
                        + "   AND NOT a.legal_hold AND a.recordings_purged_at IS NULL"
                        + "   AND a.anchor_at < now() - (? || ' days')::interval",
                recordingDays);
    }
}
