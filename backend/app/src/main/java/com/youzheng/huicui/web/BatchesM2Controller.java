package com.youzheng.huicui.web;

import com.youzheng.huicui.common.DataScope;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.common.RoleResponse;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.BatchCoordinatorRef;
import com.youzheng.huicui.web.dto.BatchPlatformView;
import com.youzheng.huicui.web.dto.BatchPropertyView;
import com.youzheng.huicui.web.dto.BatchProviderView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * M2 批次读端点（契约 listBatches / getBatch；基路径 /v1 由 context-path 提供）。
 * 类名 BatchesM2Controller 避免与 demo 风格的资源 Controller 命名冲突。
 *
 * 横切落地：
 *   - x-data-scope=range：经 project.org_id 关联裁剪（M2 读阶段以 own-org 等价实现，平台见全量；
 *     SE 三维 range 待 DataScope.range 落地后替换，见 TODO）。PROVIDER 主体追加 provider_id 裁剪（只见派给自己的批次）。
 *   - x-response-by-role：按 RoleResponse.of(s) 选 BatchPlatform/Property/Provider 三独立 record，
 *     资金双线字段级物理隔离（平台双线全含 / 物业只收佣 / 服务商只付佣 BR-M9-11）。
 *   - 无 x-permission（两端点契约均未声明 x-permission）。
 *
 * 列名核对结论见类尾注释。
 */
@RestController
public class BatchesM2Controller {

    private final JdbcTemplate jdbc;

    public BatchesM2Controller(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 批次列表 scope 片段（按 orgType 三分支，count 与 page 共用同一 where 故只追加一次即覆盖两处查询）。
     *   PROVIDER → b.provider_id=本组织（承接服务商，批次粒度权威为 b.provider_id）；
     *   PROPERTY → p.org_id=本组织（项目所属物业 org）；
     *   PLATFORM → 不追加（全量）。
     * 注意：服务商 org_id 永不等于项目所属物业 p.org_id，绝不可叠加 p.org_id 裁剪（否则服务商列表恒空）。
     */
    private static void appendBatchScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND b.provider_id = ?");
            args.add(Long.valueOf(s.orgId()));
        } else if ("PROPERTY".equals(s.orgType())) {
            where.append(" AND p.org_id = ?");
            args.add(Long.valueOf(s.orgId()));
        }
        // PLATFORM：不限。
    }

    /** SQL 行投影（DB 真列名 → 字段）。比率列 NUMERIC(6,4) 映射 BigDecimal，按契约 Rate=number 原样返回。 */
    private record BatchRow(
            long id, long projectId, String code, Long providerId,
            BigDecimal commInRate, Boolean commInInherited, BigDecimal payOutRate,
            String status) {}

    /**
     * GET /batches —— 批次列表（按 projectId/status + scope 过滤，分页）。
     * 返回 BatchPage{items:BatchView[],meta}；逐项按当前主体单一视角套三视图裁剪。
     * coordinators 列表项省略（BatchBase coordinators 可选；明细端点 getBatch 才 JOIN 填充）。
     */
    @GetMapping("/batches")
    public Page<Object> listBatches(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (projectId != null && !projectId.isBlank()) {
            where.append(" AND b.project_id = ?");
            args.add(Long.valueOf(projectId));
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND b.status = ?");
            args.add(status);
        }
        // 批次 scope 按 orgType 分支（项目属于物业 org，故服务商绝不能叠加 p.org_id 裁剪）：
        //   PROVIDER → b.provider_id=本组织（承接服务商，批次粒度仍用 b.provider_id）；
        //   PROPERTY → p.org_id=本组织（项目所属物业）；
        //   PLATFORM → 不限（全量）。
        appendBatchScope(s, where, args);

        String fromWhere = " FROM batch b JOIN project p ON p.id = b.project_id" + where;

        Long total = jdbc.queryForObject("SELECT count(*)" + fromWhere, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<BatchRow> rows = jdbc.query(
                "SELECT b.id, b.project_id, b.no, b.provider_id, b.comm_in_rate, b.comm_in_inherited, b.pay_out_rate, b.status"
                        + fromWhere + " ORDER BY b.id DESC LIMIT ? OFFSET ?",
                BatchesM2Controller::mapRow, pageArgs.toArray());

        RoleResponse.ViewRole role = RoleResponse.of(s);
        List<Object> items = new ArrayList<>(rows.size());
        // 列表视图不逐行推导覆盖态（避免 N+1）：reduceMode/playbookMode 给 INHERIT、drift 给 false（契约该批字段可选）；
        // 真实覆盖态/drift 由明细端点 getBatch 提供。
        Override listOv = new Override("INHERIT", "INHERIT", false, false);
        for (BatchRow r : rows) items.add(toView(r, role, List.of(), listOv));
        return Page.of(items, pg, total == null ? 0 : total);
    }

    /**
     * GET /batches/{id} —— 批次详情。
     * 查 batch JOIN project 过 scope（越范围/不存在→404）；按 RoleResponse.of(s) 三分支构造，
     * 资金双线字段级隔离；coordinators 由 batch_coordinators JOIN account 填充 CoordinatorRef。
     */
    @GetMapping("/batches/{id}")
    public Object getBatch(@PathVariable String id) {
        CurrentSubject s = SubjectContext.get();
        long batchId = Long.parseLong(id);

        // 与 listBatches 同口径三分支：PROVIDER→b.provider_id；PROPERTY→p.org_id；PLATFORM→不限。
        // 修 codex MED：原 ownOrg(p.org_id)+b.provider_id 双约束使服务商列表可见但详情 false-deny 404。
        StringBuilder where = new StringBuilder(" WHERE b.id = ?");
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        appendBatchScope(s, where, args);

        List<BatchRow> found = jdbc.query(
                "SELECT b.id, b.project_id, b.no, b.provider_id, b.comm_in_rate, b.comm_in_inherited, b.pay_out_rate, b.status"
                        + " FROM batch b JOIN project p ON p.id = b.project_id" + where,
                BatchesM2Controller::mapRow, args.toArray());
        if (found.isEmpty()) {
            // 越范围与不存在统一 404（不泄露存在性）。
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        }
        BatchRow r = found.get(0);

        List<BatchCoordinatorRef> coordinators = jdbc.query(
                "SELECT a.id, a.name FROM batch_coordinators bc JOIN account a ON a.id = bc.coordinator_id"
                        + " WHERE bc.batch_id = ? ORDER BY a.id",
                (rs, i) -> new BatchCoordinatorRef(String.valueOf(rs.getLong("id")), rs.getString("name")),
                batchId);

        // BC-05 真值化：reduceMode/playbookMode 由 reduce_tier(batch_id)/playbook 关联推导，消解与 GET reduce-tiers source 不一致。
        Override ov = deriveOverride(batchId, r.projectId());
        return toView(r, RoleResponse.of(s), coordinators, ov);
    }

    /** 批次覆盖真值（reduceMode/playbookMode 真实 INHERIT/CUSTOM + BR-M2-18b drift 标记）。 */
    private record Override(String reduceMode, String playbookMode, boolean reduceDrift, boolean playbookDrift) {}

    /**
     * 推导批次覆盖态（BC-05 真值化 + BC-04 覆盖同步 BR-M2-18b）。
     * reduceMode：reduce_tier 存在 batch_id=batchId 行 → CUSTOM，否则 INHERIT。
     * reduceDrift：CUSTOM 时比对——项目级当前 max(updated_at) > 该批次覆盖行记录的 baseline_project_updated_at
     *             （V912 基线列）→ 项目级已更新而本批次仍持旧自定义，提示“一键同步”。
     * playbookMode：playbook 存在 batch_id=batchId 现行(status<>'ARCHIVED')覆盖行 → CUSTOM，否则 INHERIT（V915）。
     * playbookDrift：CUSTOM 时比对——项目级现行手册 updated_at > 覆盖行 baseline_project_updated_at（V912 列）
     *             → 项目级手册已更新而本批次仍持旧自定义，提示“一键同步”。
     */
    private Override deriveOverride(long batchId, long projectId) {
        // 批次自定义减免阶梯行（带基线）：取一行即足以判定 CUSTOM 与 drift。
        List<java.sql.Timestamp> baselines = jdbc.query(
                "SELECT baseline_project_updated_at FROM reduce_tier WHERE batch_id = ? ORDER BY id LIMIT 1",
                (rs, i) -> rs.getTimestamp("baseline_project_updated_at"),
                batchId);
        boolean reduceCustom = !baselines.isEmpty();
        boolean reduceDrift = false;
        if (reduceCustom) {
            // 项目级当前 max(updated_at)（同 project，batch_id IS NULL）。
            java.sql.Timestamp projMax = jdbc.query(
                    "SELECT max(updated_at) AS m FROM reduce_tier WHERE project_id = ? AND batch_id IS NULL",
                    rs -> rs.next() ? rs.getTimestamp("m") : null,
                    projectId);
            java.sql.Timestamp baseline = baselines.get(0);
            // 基线为 NULL（理论不应出现，回填已覆盖）按有差异处理，提示同步以补基线。
            reduceDrift = projMax != null && (baseline == null || projMax.after(baseline));
        }
        String reduceMode = reduceCustom ? "CUSTOM" : "INHERIT";

        // playbook（V915 批次级覆盖）：现行覆盖行带 baseline_project_updated_at → CUSTOM；比对项目级手册 updated_at 判 drift。
        List<java.sql.Timestamp> pbBaselines = jdbc.query(
                "SELECT baseline_project_updated_at FROM playbook"
                        + " WHERE batch_id = ? AND status <> 'ARCHIVED' ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getTimestamp("baseline_project_updated_at"),
                batchId);
        boolean playbookCustom = !pbBaselines.isEmpty();
        boolean playbookDrift = false;
        if (playbookCustom) {
            java.sql.Timestamp projPbTs = jdbc.query(
                    "SELECT updated_at FROM playbook WHERE project_id = ? AND batch_id IS NULL"
                            + " AND status <> 'ARCHIVED' ORDER BY id DESC LIMIT 1",
                    rs -> rs.next() ? rs.getTimestamp("updated_at") : null,
                    projectId);
            java.sql.Timestamp pbBaseline = pbBaselines.get(0);
            playbookDrift = projPbTs != null && (pbBaseline == null || projPbTs.after(pbBaseline));
        }
        String playbookMode = playbookCustom ? "CUSTOM" : "INHERIT";

        return new Override(reduceMode, playbookMode, reduceDrift, playbookDrift);
    }

    /** 行映射：no→code，provider_id 可空。 */
    private static BatchRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long providerId = rs.getLong("provider_id");
        Long provider = rs.wasNull() ? null : providerId;
        return new BatchRow(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("no"), provider,
                rs.getBigDecimal("comm_in_rate"), (Boolean) rs.getObject("comm_in_inherited"),
                rs.getBigDecimal("pay_out_rate"), rs.getString("status"));
    }

    /**
     * 按视角构造对应物理 record（资金双线字段级隔离 BR-M9-11）。
     * reduceMode/playbookMode：由调用方推导的真实覆盖态（BC-05 真值化）经 Override 传入。
     *   - reduceMode：reduce_tier(batch_id) 存在 → CUSTOM 否则 INHERIT（与 GET /batches/{id}/reduce-tiers source 一致）。
     *   - playbookMode：DDL 无批次级手册存储 → 恒 INHERIT（见 deriveOverride 注释）。
     * BR-M2-18b 的 reduceDrift/playbookDrift 由 ov 算出并落入三视图 record 可选字段（契约 BatchBase 定义为 optional boolean）：
     *   - reduceDrift：CUSTOM 批次项目级减免已更新而本批未同步时 true，驱动前端“一键同步”告警 banner。
     *   - playbookDrift：无批次级手册存储 → 恒 false（已知降级，照实传，不造假数据）。
     */
    private static Object toView(BatchRow r, RoleResponse.ViewRole role,
                                 List<BatchCoordinatorRef> coordinators, Override ov) {
        String idStr = String.valueOf(r.id());
        String projectIdStr = String.valueOf(r.projectId());
        String providerIdStr = r.providerId() == null ? null : String.valueOf(r.providerId());
        final String reduceMode = ov.reduceMode();
        final String playbookMode = ov.playbookMode();
        final Boolean reduceDrift = ov.reduceDrift();
        final Boolean playbookDrift = ov.playbookDrift();
        return switch (role) {
            case PROVIDER -> new BatchProviderView(
                    idStr, projectIdStr, r.code(), providerIdStr, coordinators, r.status(),
                    reduceMode, playbookMode, "PROVIDER", r.payOutRate(), reduceDrift, playbookDrift);
            case PROPERTY -> new BatchPropertyView(
                    idStr, projectIdStr, r.code(), providerIdStr, coordinators, r.status(),
                    reduceMode, playbookMode, "PROPERTY", r.commInRate(), r.commInInherited(), reduceDrift, playbookDrift);
            case PLATFORM -> new BatchPlatformView(
                    idStr, projectIdStr, r.code(), providerIdStr, coordinators, r.status(),
                    reduceMode, playbookMode, "PLATFORM", r.commInRate(), r.commInInherited(), r.payOutRate(), reduceDrift, playbookDrift);
        };
    }

    // ===== SQL 列名 ↔ 契约字段核对（DDL V1 batch / V2 batch_coordinators）=====
    // batch.id              -> id            (string，应用层 toString)
    // batch.project_id      -> projectId
    // batch.no              -> code          (契约改名避 YAML1.1 布尔陷阱)
    // batch.provider_id     -> providerId    (nullable，未派单为 null)
    // batch.comm_in_rate    -> commInRate    (NUMERIC(6,4)→Rate number，原样百分比，平台/物业含)
    // batch.comm_in_inherited-> commInInherited (平台/物业含)
    // batch.pay_out_rate    -> payOutRate    (平台/服务商含；物业物理不含)
    // batch.status          -> status
    // batch_coordinators(batch_id,coordinator_id) JOIN account -> coordinators[CoordinatorRef{id,name}]
    // reduceMode：由 reduce_tier(batch_id) 推导真值(存在→CUSTOM 否则 INHERIT)，与 GET reduce-tiers source 一致(BC-05)。
    // playbookMode：DDL playbook 无 batch_id → 恒 INHERIT(批次手册经 project 折叠)。
    // reduceDrift/playbookDrift(BR-M2-18b)：deriveOverride 算出后落入三视图 record 可选字段；playbookDrift 恒 false(无批次级手册存储·已知降级)。
}
