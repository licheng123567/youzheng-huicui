package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.DataScope;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.common.RoleResponse;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.ProjectDtos.CoordinatorRef;
import com.youzheng.huicui.web.dto.ProjectDtos.FeeRow;
import com.youzheng.huicui.web.dto.ProjectDtos.Litigation;
import com.youzheng.huicui.web.dto.ProjectDtos.Project;
import com.youzheng.huicui.web.dto.ProjectDtos.ProjectForProvider;
import com.youzheng.huicui.web.dto.ProjectDtos.ReduceTier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * M2 projects 资源读端点（契约 listProjects / getProject），横切层范式 + scaffold 共享助手实现。
 * 与 demo 用 {@link ProjectsController} 物理隔离（新类名 ProjectsM2，不覆盖演示端点）。
 *
 * 横切落地：
 *  - x-data-scope=range：M2 读阶段以 DataScope.ownOrg(s,"p.org_id") 落地（平台全量 / 物业本组织；
 *    服务商三维 range 留待写端点接入，读阶段 own_org 等价裁剪 SE 数据范围）。
 *  - 无 x-permission（仅认证 + scope）：JwtAuthFilter 已保证主体存在，故不标 @RequirePermission。
 *  - x-response-by-role（getProject）：按 RoleResponse.projectViewRole(s) 选 Project / ProjectForProvider
 *    两个物理隔离 record（资金双线 BR-M1-06 / BR-M9-11）。
 *  - 越范围/不存在统一 404 NOT_FOUND_404（避免存在性泄漏）。
 *
 * 金额列 *_cents 原样以「分(integer)」返回（契约 Money）。
 */
@RestController
public class ProjectsM2Controller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public ProjectsM2Controller(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---------------------------------------------------------------------
    // [1] GET /projects —— listProjects（分页 + q 模糊 name/org_name + status）
    // ---------------------------------------------------------------------
    @GetMapping("/projects")
    public Page<Project> listProjects(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status) {

        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);
        DataScope.Fragment scope = DataScope.ownOrg(s, "p.org_id");

        // 同一 WHERE 片段 + 参数两处复用（count 与 items）
        StringBuilder where = new StringBuilder(" WHERE 1=1").append(scope.sql());
        List<Object> args = new ArrayList<>(List.of(scope.params()));
        if (q != null && !q.isBlank()) {
            where.append(" AND (p.name ILIKE ? OR p.org_name ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND p.status = ?");
            args.add(status.trim());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM project p" + where, Long.class, args.toArray());
        long totalVal = total == null ? 0L : total;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        // 列表固定 Project schema（roleResponseRule 第4条），coordinators/reduceTiers 省略（详情才给）
        List<Project> items = totalVal == 0 ? List.of() : jdbc.query(
                "SELECT p.* FROM project p" + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?",
                projectRowMapper(/*withDetail*/ false), pageArgs.toArray());

        return Page.of(items, pg, totalVal);
    }

    // ---------------------------------------------------------------------
    // [2] GET /projects/{id} —— getProject（按角色返回 Project / ProjectForProvider）
    // ---------------------------------------------------------------------
    @GetMapping("/projects/{id}")
    public Object getProject(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        DataScope.Fragment scope = DataScope.ownOrg(s, "p.org_id");

        long projectId;
        try {
            projectId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw notFound();   // 非法 id 形态统一 404，避免存在性泄漏
        }

        List<Object> args = new ArrayList<>();
        args.add(projectId);
        args.addAll(List.of(scope.params()));
        // 越范围/不存在统一 404：scope 片段直接拼进 WHERE
        List<Project> rows = jdbc.query(
                "SELECT p.* FROM project p WHERE p.id = ?" + scope.sql(),
                projectRowMapper(/*withDetail*/ false), args.toArray());
        if (rows.isEmpty()) throw notFound();
        Project base = rows.get(0);

        // 项目级减免阶梯（batch_id IS NULL）——两视角都给
        List<ReduceTier> tiers = jdbc.query(
                "SELECT discount, cap_cents, waive_penalty, decide FROM reduce_tier"
                        + " WHERE project_id = ? AND batch_id IS NULL ORDER BY id",
                (rs, i) -> new ReduceTier(
                        rs.getString("discount"),
                        (Long) rs.getObject("cap_cents"),
                        rs.getBoolean("waive_penalty"),
                        rs.getString("decide")),
                projectId);

        if (!"PROVIDER".equals(RoleResponse.projectViewRole(s))) {
            // 物业/平台 → Project（全量：财务汇总 + coordinators + reduceTiers）
            long dueTotal = sumCaseDue(projectId);
            long repayTotal = sumRepay(projectId);   // TODO 对账模块接入（M2 占位）
            List<CoordinatorRef> coordinators = jdbc.query(
                    "SELECT a.id AS id, a.name AS name FROM project_coordinators pc"
                            + " JOIN account a ON a.id = pc.coordinator_id"
                            + " WHERE pc.project_id = ? ORDER BY a.id",
                    (rs, i) -> new CoordinatorRef(String.valueOf(rs.getLong("id")), rs.getString("name")),
                    projectId);
            return new Project(
                    "PROPERTY_PLATFORM", base.id(), base.name(), base.area(), base.province(),
                    base.city(), base.district(), base.propCompany(), base.contractType(),
                    base.feeRows(), base.feeCycle(), base.penalty(), base.payInfo(),
                    base.commInRate(), base.org(), base.status(),
                    dueTotal, repayTotal, coordinators, tiers, base.litigation());
        }

        // 服务商 → ProjectForProvider（物理不含 commInRate / 财务汇总；feeStd 汇总展示串）
        return new ProjectForProvider(
                "PROVIDER", base.id(), base.name(), base.area(), base.propCompany(),
                base.contractType(), feeStdOf(base.feeRows()), base.feeCycle(),
                base.penalty(), base.payInfo(), tiers, base.litigation(), base.status());
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** project 行 → Project record（commInRate/coordinators/财务汇总在详情分支按需补全）。 */
    private RowMapper<Project> projectRowMapper(boolean withDetail) {
        return (ResultSet rs, int i) -> new Project(
                "PROPERTY_PLATFORM",
                String.valueOf(rs.getLong("id")),
                rs.getString("name"),
                rs.getString("area"),
                rs.getString("province"),
                rs.getString("city"),
                rs.getString("district"),
                rs.getString("prop_company"),
                rs.getString("contract_type"),
                parseFeeRows(rs.getString("fee_rows")),
                rs.getString("fee_cycle"),
                rs.getString("penalty"),
                rs.getString("pay_info"),
                numOrNull(rs, "comm_in_rate"),
                rs.getString("org_name"),
                rs.getString("status"),
                null,                       // dueTotalCents：列表省略，详情补
                null,                       // repayTotalCents：同上
                null,                       // coordinators：列表省略
                null,                       // reduceTiers：列表省略
                litigationOf(rs));
    }

    private static Double numOrNull(ResultSet rs, String col) throws SQLException {
        java.math.BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.doubleValue();
    }

    /** litigation 由 project.credit_code/legal/addr 三列组装；三者全空则返回 null。 */
    private static Litigation litigationOf(ResultSet rs) throws SQLException {
        String credit = rs.getString("credit_code");
        String legal = rs.getString("legal");
        String addr = rs.getString("addr");
        if (credit == null && legal == null && addr == null) return null;
        return new Litigation(credit, legal, addr);
    }

    /** fee_rows jsonb [{biz,std}] → List<FeeRow>；解析失败/空 → 空列表。 */
    private List<FeeRow> parseFeeRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return om.readValue(json, om.getTypeFactory()
                    .constructCollectionType(List.class, FeeRow.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 服务商视角 feeStd：把 [{biz,std}] 汇总成 "业务:标准" 展示串。 */
    private static String feeStdOf(List<FeeRow> rows) {
        if (rows == null || rows.isEmpty()) return null;
        return rows.stream()
                .map(r -> (r.biz() == null ? "" : r.biz())
                        + (r.std() == null ? "" : (r.biz() == null ? "" : ": ") + r.std()))
                .filter(x -> !x.isBlank())
                .collect(Collectors.joining("；"));
    }

    /** dueTotalCents：case 聚合 sum(due_cents)（口径占位，对账细化留待 M9）。 */
    private long sumCaseDue(long projectId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(sum(due_cents),0) FROM \"case\" WHERE project_id = ?",
                Long.class, projectId);
        return v == null ? 0L : v;
    }

    /** repayTotalCents：回款聚合占位（TODO 对账模块接入 repay_line 口径）。 */
    private long sumRepay(long projectId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(sum(amount_cents),0) FROM repay_line"
                        + " WHERE case_id IN (SELECT id FROM \"case\" WHERE project_id = ?)"
                        + " AND reversed = false",
                Long.class, projectId);
        return v == null ? 0L : v;
    }

    private static ApiException notFound() {
        return new ApiException(BizError.NOT_FOUND_404, "project not found");
    }
}
