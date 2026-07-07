package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 易保全备案态轮询回填：对已提交（有 provider_evidence_id、尚无 preservation_id）的 evidence_ebq_item，
 * 调 queryEvidenceDetail 拿备案号；就绪则回填备案号 + preservationInfo 链上信息，item 置 PRESERVED；
 * 某 evidence 的全部 item 均 PRESERVED → evidence 置 ISSUED、issued_at=now、cert_no=首个备案号。
 * 备案证书/链信息约保全成功 10 分钟后就绪，故轮询多轮渐次回填。
 */
@Service
public class EvidencePollService {

    private static final Logger log = LoggerFactory.getLogger(EvidencePollService.class);
    private final JdbcTemplate jdbc;
    private final EbaoquanClient ebaoquan;

    public EvidencePollService(JdbcTemplate jdbc, EbaoquanClient ebaoquan) {
        this.jdbc = jdbc;
        this.ebaoquan = ebaoquan;
    }

    private record PendingItem(long id, long evidenceId, long providerEvidenceId) {}

    /** 轮询一轮，返回本轮回填成功的 item 数。单 item 异常隔离（不影响其它）。 */
    public int pollOnce() {
        if (!ebaoquan.isEnabled()) return 0;
        List<PendingItem> pend = jdbc.query(
                "SELECT id, evidence_id, provider_evidence_id FROM evidence_ebq_item"
                        + " WHERE preservation_id IS NULL AND provider_evidence_id IS NOT NULL AND status = 'SUBMITTED'"
                        + " ORDER BY id LIMIT 100",
                (rs, i) -> new PendingItem(rs.getLong("id"), rs.getLong("evidence_id"), rs.getLong("provider_evidence_id")));
        int filled = 0;
        for (PendingItem it : pend) {
            try {
                if (fillItem(it)) filled++;
            } catch (RuntimeException e) {
                log.warn("易保全备案回填失败 item={} providerEvidenceId={}: {}", it.id, it.providerEvidenceId, e.toString());
            }
        }
        return filled;
    }

    private boolean fillItem(PendingItem it) {
        JsonNode detail = ebaoquan.queryEvidenceDetail(it.providerEvidenceId);
        long presId = detail.path("preservationId").asLong(0);
        if (presId <= 0) return false;   // 尚未备案成功，下轮再试
        String chainTx = null, gznet = null, ant = null;
        try {
            JsonNode info = ebaoquan.preservationInfo(presId);
            chainTx = txt(info, "ebqChainTransHash");
            gznet = txt(info, "gznetId");
            ant = txt(info, "antId");
        } catch (RuntimeException e) {
            log.info("易保全链信息暂不可取（preservationId={}），仅回填备案号: {}", presId, e.toString());
        }
        jdbc.update(
                "UPDATE evidence_ebq_item SET preservation_id = ?, chain_tx_hash = ?, gznet_id = ?, ant_id = ?,"
                        + " status = 'PRESERVED', updated_at = now() WHERE id = ?",
                presId, chainTx, gznet, ant, it.id);
        promoteEvidenceIfComplete(it.evidenceId);
        return true;
    }

    /** 该 evidence 已无未备案 item → 置 ISSUED，cert_no=首个备案号。 */
    private void promoteEvidenceIfComplete(long evidenceId) {
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_ebq_item WHERE evidence_id = ? AND preservation_id IS NULL",
                Integer.class, evidenceId);
        if (pending != null && pending == 0) {
            Long firstPres = jdbc.query(
                    "SELECT preservation_id FROM evidence_ebq_item WHERE evidence_id = ? AND preservation_id IS NOT NULL ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong(1) : null, evidenceId);
            jdbc.update(
                    "UPDATE evidence SET status = 'ISSUED', cert_no = ?, issued_at = now(), updated_at = now()"
                            + " WHERE id = ? AND status = 'ISSUING'",
                    firstPres == null ? null : String.valueOf(firstPres), evidenceId);
        }
    }

    private static String txt(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
