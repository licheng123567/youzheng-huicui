package com.youzheng.huicui.web;

import com.youzheng.huicui.common.DataScope;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.DeliveryRecordDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 送达管理（deliveries）：协调员「送达管理」页的送达记录列表——由已上传的送达凭证附件聚合而成。
 * 「法务案件列表」概念已移除（法务只是催收跟进手段之一）；本页只列送达凭证（case_attachment.delivery_type 非空），
 * 来源两类：app 扫码/手机上传（session_token 非空）与 PC 后台直传（session_token 为空）。
 *
 * 端点（基路径 /v1 由 context-path 提供，注解写裸路径）：
 *   GET /deliveries   listDeliveries   | 无 perm（靠 scope 控可见） scope=物业 own-org(+PC 协调集) | 200 DeliveryRecordPage
 *
 * 隔离（仿 EvidenceM6Controller.appendEvidenceScope）：服务商恒空；平台 SA 全量/SE 三维；
 *   物业 PL 本组织全量；物业 PC 本组织 AND 案件 ∈ 本人协调集（B-02）。
 * 送达状态字段（是否存证/存证态）由关联 DELIVERY 存证（evidence.ref_ids @> 本附件 id）派生；无关联=未存证。
 * 附件字节/证书等二进制经既有端点获取（GET /attachments/{id}、GET /evidence/{id}/certificate），本端点只返元数据。
 */
@RestController
public class DeliveriesController {

    private final JdbcTemplate jdbc;

    public DeliveriesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── GET /deliveries  listDeliveries ──────────────────────────────────────────
    @GetMapping("/deliveries")
    public Page<DeliveryRecordDto> listDeliveries(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE ca.delivery_type IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendDeliveryScope(s, where, args);

        // JOIN case/project/batch 供 scope 裁剪（p.org_id/p.area/c.provider_id/c.project_id/c.batch_id）。
        String from = "FROM case_attachment ca"
                + " JOIN \"case\" c ON c.id = ca.case_id"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id";
        Long total = jdbc.queryForObject("SELECT count(*) " + from + where, Long.class, args.toArray());

        // 关联最新 DELIVERY 存证态（ref_ids JSONB 数组含本附件 id 的字符串形态）：有=已存证，取 id/status 供下证书。
        String evJoin = " LEFT JOIN LATERAL ("
                + "   SELECT e.id, e.status FROM evidence e"
                + "   WHERE e.scene = 'DELIVERY' AND e.ref_ids @> to_jsonb(ca.id::text)"
                + "   ORDER BY e.id DESC LIMIT 1"
                + " ) ev ON true";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<DeliveryRecordDto> items = jdbc.query(
                "SELECT ca.id, ca.case_id, c.room, p.name AS project_name, b.no AS batch_no,"
                        + " ca.created_at, ca.delivery_type, ca.session_token, ca.filename,"
                        + " ev.id AS evidence_id, ev.status AS evidence_status "
                        + from + evJoin + where
                        + " ORDER BY ca.created_at DESC LIMIT ? OFFSET ?",
                mapper(), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    private RowMapper<DeliveryRecordDto> mapper() {
        return (ResultSet rs, int i) -> {
            String token = rs.getString("session_token");
            Timestamp ts = rs.getTimestamp("created_at");
            long evId = rs.getLong("evidence_id");
            boolean hasEv = !rs.wasNull();
            return new DeliveryRecordDto(
                    String.valueOf(rs.getLong("id")),
                    String.valueOf(rs.getLong("case_id")),
                    rs.getString("room"),
                    rs.getString("project_name"),
                    rs.getString("batch_no"),
                    ts == null ? null : ts.toInstant().toString(),
                    rs.getString("delivery_type"),
                    token == null ? "BACKEND" : "APP",
                    rs.getString("filename"),
                    hasEv,
                    hasEv ? String.valueOf(evId) : null,
                    hasEv ? rs.getString("evidence_status") : null);
        };
    }

    /**
     * 送达可见性隔离（仿 appendEvidenceScope，以 p.org_id 为物业归属）：
     *   服务商→恒空(1=0)；平台 SA 全量/SE 三维(data_range)；物业 PL 本组织；物业 PC 本组织 AND 协调集(B-02)。
     */
    private void appendDeliveryScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if ("PROVIDER".equals(s.orgType())) {                 // 服务商不可见送达 → 空页
            where.append(" AND 1=0");
            return;
        }
        if (s.isPlatform()) {                                 // SA 全量；SE 按 data_range 三维（无 PC 维，传 null）
            DataScope.appendRange(s, where, args, "c.provider_id", "p.org_id", "p.area", null, null);
            return;
        }
        where.append(" AND p.org_id = ?");
        args.add(orgIdOrThrow(s));
        if (s.isPC()) {
            DataScope.appendPcCoordinatorSet(s, where, args, "c.project_id", "c.batch_id");
        }
    }

    private static long orgIdOrThrow(CurrentSubject s) {
        try {
            return Long.parseLong(s.orgId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "主体无有效组织");
        }
    }
}
