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

        // scope=range（经 project.org_id 关联）。M2 读阶段以 ownOrg 等价；TODO: 接 SE 三维 range。
        DataScope.Fragment scope = DataScope.ownOrg(s, "p.org_id");

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
        where.append(scope.sql());
        for (Object p : scope.params()) args.add(p);
        // 服务商主体只见已派给自己的批次（provider_id=s.orgId）。
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND b.provider_id = ?");
            args.add(Long.valueOf(s.orgId()));
        }

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
        for (BatchRow r : rows) items.add(toView(r, role, List.of()));
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

        DataScope.Fragment scope = DataScope.ownOrg(s, "p.org_id");
        StringBuilder where = new StringBuilder(" WHERE b.id = ?");
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        where.append(scope.sql());
        for (Object p : scope.params()) args.add(p);
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND b.provider_id = ?");
            args.add(Long.valueOf(s.orgId()));
        }

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

        return toView(r, RoleResponse.of(s), coordinators);
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
     * reduceMode/playbookMode：V1 batch 表无对应列 → 先以 'INHERIT' 常量占位。
     * TODO(M2 写阶段)：确认列或由 reduce_tier(batch_id)/playbook 关联推导 source(INHERITED/CUSTOM→INHERIT/CUSTOM)。
     */
    private static Object toView(BatchRow r, RoleResponse.ViewRole role, List<BatchCoordinatorRef> coordinators) {
        String idStr = String.valueOf(r.id());
        String projectIdStr = String.valueOf(r.projectId());
        String providerIdStr = r.providerId() == null ? null : String.valueOf(r.providerId());
        final String reduceMode = "INHERIT";    // TODO 占位
        final String playbookMode = "INHERIT";   // TODO 占位
        return switch (role) {
            case PROVIDER -> new BatchProviderView(
                    idStr, projectIdStr, r.code(), providerIdStr, coordinators, r.status(),
                    reduceMode, playbookMode, "PROVIDER", r.payOutRate());
            case PROPERTY -> new BatchPropertyView(
                    idStr, projectIdStr, r.code(), providerIdStr, coordinators, r.status(),
                    reduceMode, playbookMode, "PROPERTY", r.commInRate(), r.commInInherited());
            case PLATFORM -> new BatchPlatformView(
                    idStr, projectIdStr, r.code(), providerIdStr, coordinators, r.status(),
                    reduceMode, playbookMode, "PLATFORM", r.commInRate(), r.commInInherited(), r.payOutRate());
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
    // reduceMode/playbookMode：契约 BatchBase 有，V1 batch 表无列 → 'INHERIT' 占位 + TODO
}
