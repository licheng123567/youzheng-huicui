package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.DataScope;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.common.RoleResponse;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.ProjectDtos.CoordinatorRef;
import com.youzheng.huicui.web.dto.ProjectDtos.FeeRow;
import com.youzheng.huicui.web.dto.ProjectDtos.Litigation;
import com.youzheng.huicui.web.dto.ProjectDtos.Project;
import com.youzheng.huicui.web.dto.ProjectDtos.ProjectForProvider;
import com.youzheng.huicui.web.dto.ProjectDtos.ReduceTier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * M2 projects 资源读端点（契约 listProjects / getProject），横切层范式 + scaffold 共享助手实现。
 * 类名带 M2 后缀（历史：曾与横切层验证的 demo 类 ProjectsController 并存，demo 已删）。
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
        // 列表补聚合列（批次数/在催/法务/已结清/应收/回款）——原型项目列表列，子查询按 project 汇总。
        String aggCols = ", (SELECT count(*) FROM batch b WHERE b.project_id = p.id) AS batch_count"
                + ", (SELECT count(*) FROM \"case\" c WHERE c.project_id = p.id AND c.status = 'IN_PROGRESS') AS active_cases"
                + ", (SELECT count(*) FROM \"case\" c WHERE c.project_id = p.id AND c.legal_stage IS NOT NULL AND c.legal_stage <> 'NONE') AS legal_count"
                + ", (SELECT count(*) FROM \"case\" c WHERE c.project_id = p.id AND c.status = 'SETTLED') AS settled_count"
                + ", (SELECT COALESCE(SUM(c.due_cents),0) FROM \"case\" c WHERE c.project_id = p.id) AS due_total"
                + ", (SELECT COALESCE(SUM(rl.amount_cents),0) FROM repay_line rl JOIN \"case\" c ON c.id = rl.case_id"
                + "     WHERE c.project_id = p.id AND rl.reversed = false) AS repay_total";
        List<Project> items = totalVal == 0 ? List.of() : jdbc.query(
                "SELECT p.*" + aggCols + " FROM project p" + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?",
                projectListRowMapper(), pageArgs.toArray());

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
                    base.contractName(), base.servicePeriod(),
                    base.feeRows(), base.feeCycle(), base.penalty(), base.payInfo(),
                    base.corpAccount(), base.wxQrUrl(), base.reducePolicy(),
                    base.commInRate(), base.org(), base.status(),
                    dueTotal, repayTotal, coordinators, tiers, base.litigation(),
                    null, null, null, null);
        }

        // 服务商 → ProjectForProvider（物理不含 commInRate / 财务汇总；feeStd 汇总展示串）
        return new ProjectForProvider(
                "PROVIDER", base.id(), base.name(), base.area(), base.propCompany(),
                base.contractType(), base.contractName(), base.servicePeriod(),
                feeStdOf(base.feeRows()), base.feeCycle(),
                base.penalty(), base.payInfo(), base.corpAccount(), base.wxQrUrl(),
                base.reducePolicy(), tiers, base.litigation(), base.status());
    }

    // ---------------------------------------------------------------------
    // [3] POST /projects — createProject（proj.edit, own-org：物业建本组织项目；SA 建于平台组织）
    // ---------------------------------------------------------------------
    @PostMapping("/projects")
    @RequirePermission("proj.edit")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Project createProject(@RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        requireOrgActive(s);                       // v1.22.0 组织停用=停新单：不能再新建项目（存量项目/案件照常）
        String name = reqStr(body, "name"), area = reqStr(body, "area");
        java.math.BigDecimal rate = reqRate(body, "commInRate");   // Rate 分数 0-1
        Long id = jdbc.queryForObject(
                "INSERT INTO project(org_id, name, org_name, area, province, city, district,"
                        + " prop_company, contract_type, contract_name, service_period,"
                        + " fee_rows, fee_cycle, penalty, pay_info, corp_account, wx_qr_url, reduce_policy, comm_in_rate, status)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,'ACTIVE') RETURNING id",
                Long.class, Long.parseLong(s.orgId()), name, s.orgName(), area,
                str(body, "province"), str(body, "city"), str(body, "district"),
                str(body, "propCompany"), str(body, "contractType"),
                str(body, "contractName"), str(body, "servicePeriod"),
                feeRowsJson(body.get("feeRows")),
                str(body, "feeCycle"), str(body, "penalty"), str(body, "payInfo"),
                str(body, "corpAccount"), str(body, "wxQrUrl"), str(body, "reducePolicy"), rate);
        return fetchPlatformProject(id);
    }

    // ---------------------------------------------------------------------
    // [4] PUT /projects/{id} — updateProject（proj.edit, own-org）
    // ---------------------------------------------------------------------
    @PutMapping("/projects/{id}")
    @RequirePermission("proj.edit")
    @Transactional
    public Project updateProject(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long projectId = parseIdOr404(id);
        requireOwnOrgProject(s, projectId);   // 不存在→404 / 越组织→403
        String name = reqStr(body, "name"), area = reqStr(body, "area");
        java.math.BigDecimal rate = reqRate(body, "commInRate");
        jdbc.update(
                "UPDATE project SET name=?, area=?, province=?, city=?, district=?, prop_company=?,"
                        + " contract_type=?, contract_name=?, service_period=?,"
                        + " fee_rows=?::jsonb, fee_cycle=?, penalty=?, pay_info=?, corp_account=?, wx_qr_url=?, reduce_policy=?,"
                        + " comm_in_rate=?, updated_at=now() WHERE id=?",
                name, area, str(body, "province"), str(body, "city"), str(body, "district"), str(body, "propCompany"),
                str(body, "contractType"), str(body, "contractName"), str(body, "servicePeriod"),
                feeRowsJson(body.get("feeRows")), str(body, "feeCycle"), str(body, "penalty"),
                str(body, "payInfo"), str(body, "corpAccount"), str(body, "wxQrUrl"), str(body, "reducePolicy"),
                rate, projectId);
        return fetchPlatformProject(projectId);
    }

    /** 创建/更新后重取完整平台视角 Project（含 tiers/coords/财务汇总）。 */
    private Project fetchPlatformProject(long projectId) {
        List<Project> rows = jdbc.query("SELECT p.* FROM project p WHERE p.id = ?", projectRowMapper(false), projectId);
        if (rows.isEmpty()) throw notFound();
        Project base = rows.get(0);
        List<ReduceTier> tiers = jdbc.query(
                "SELECT discount, cap_cents, waive_penalty, decide FROM reduce_tier WHERE project_id = ? AND batch_id IS NULL ORDER BY id",
                (rs, i) -> new ReduceTier(rs.getString("discount"), (Long) rs.getObject("cap_cents"), rs.getBoolean("waive_penalty"), rs.getString("decide")),
                projectId);
        List<CoordinatorRef> coords = jdbc.query(
                "SELECT a.id AS id, a.name AS name FROM project_coordinators pc JOIN account a ON a.id = pc.coordinator_id WHERE pc.project_id = ? ORDER BY a.id",
                (rs, i) -> new CoordinatorRef(String.valueOf(rs.getLong("id")), rs.getString("name")), projectId);
        return new Project("PROPERTY_PLATFORM", base.id(), base.name(), base.area(), base.province(),
                base.city(), base.district(), base.propCompany(), base.contractType(),
                base.contractName(), base.servicePeriod(),
                base.feeRows(), base.feeCycle(), base.penalty(), base.payInfo(),
                base.corpAccount(), base.wxQrUrl(), base.reducePolicy(),
                base.commInRate(), base.org(), base.status(),
                sumCaseDue(projectId), sumRepay(projectId), coords, tiers, base.litigation(),
                null, null, null, null);
    }

    private void requireOwnOrgProject(CurrentSubject s, long projectId) {
        Long orgId = jdbc.query("SELECT org_id FROM project WHERE id = ?", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (orgId == null) throw notFound();
        if (!s.isPlatform() && orgId != Long.parseLong(s.orgId())) throw new ApiException(BizError.PERM_403, "无权操作非本组织项目");
    }

    private long parseIdOr404(String id) {
        try { return Long.parseLong(id); } catch (NumberFormatException e) { throw notFound(); }
    }
    private String reqStr(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        if (v == null || String.valueOf(v).isBlank()) throw new ApiException(BizError.VALIDATION_422, k + " 必填");
        return String.valueOf(v);
    }
    private String str(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v == null ? null : String.valueOf(v);
    }
    private java.math.BigDecimal reqRate(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        if (v == null) throw new ApiException(BizError.VALIDATION_422, k + " 必填");
        java.math.BigDecimal r;
        try { r = new java.math.BigDecimal(String.valueOf(v)); } catch (Exception e) { throw new ApiException(BizError.VALIDATION_422, k + " 非法"); }
        if (r.signum() < 0 || r.compareTo(java.math.BigDecimal.ONE) > 0) throw new ApiException(BizError.VALIDATION_422, k + " 须为 0-1 分数");
        return r;
    }
    private String feeRowsJson(Object feeRows) {
        if (feeRows == null) return null;
        try { return om.writeValueAsString(feeRows); } catch (Exception e) { return null; }
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
                rs.getString("contract_name"),
                rs.getString("service_period"),
                parseFeeRows(rs.getString("fee_rows")),
                rs.getString("fee_cycle"),
                rs.getString("penalty"),
                rs.getString("pay_info"),
                rs.getString("corp_account"),
                rs.getString("wx_qr_url"),
                rs.getString("reduce_policy"),
                numOrNull(rs, "comm_in_rate"),
                rs.getString("org_name"),
                rs.getString("status"),
                null,                       // dueTotalCents：列表省略，详情补
                null,                       // repayTotalCents：同上
                null,                       // coordinators：列表省略
                null,                       // reduceTiers：列表省略
                litigationOf(rs),
                null, null, null, null);    // 聚合列仅 projectListRowMapper 填
    }

    /** 列表专用 mapper：共享列 + 聚合列（批次数/在催/法务/已结清/应收/回款），供项目列表原型列展示。 */
    private RowMapper<Project> projectListRowMapper() {
        return (ResultSet rs, int i) -> new Project(
                "PROPERTY_PLATFORM",
                String.valueOf(rs.getLong("id")),
                rs.getString("name"), rs.getString("area"),
                rs.getString("province"), rs.getString("city"), rs.getString("district"),
                rs.getString("prop_company"), rs.getString("contract_type"), rs.getString("contract_name"),
                rs.getString("service_period"), parseFeeRows(rs.getString("fee_rows")), rs.getString("fee_cycle"),
                rs.getString("penalty"), rs.getString("pay_info"), rs.getString("corp_account"),
                rs.getString("wx_qr_url"), rs.getString("reduce_policy"), numOrNull(rs, "comm_in_rate"),
                rs.getString("org_name"), rs.getString("status"),
                longOrNullCol(rs, "due_total"), longOrNullCol(rs, "repay_total"),
                null, null, litigationOf(rs),
                intOrNullCol(rs, "batch_count"), intOrNullCol(rs, "active_cases"),
                intOrNullCol(rs, "legal_count"), intOrNullCol(rs, "settled_count"));
    }

    private static Long longOrNullCol(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col); return rs.wasNull() ? null : v;
    }
    private static Integer intOrNullCol(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col); return rs.wasNull() ? null : v;
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

    /** v1.22.0 BR-M1-15：组织被平台停用 → 停新单（不能新建项目/导入批次），但存量作业与结算不受影响。 */
    private void requireOrgActive(CurrentSubject s) {
        if (s.isPlatform() || s.orgId() == null) return;
        String st = jdbc.query("SELECT status FROM org WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, Long.parseLong(s.orgId()));
        if ("DISABLED".equals(st)) {
            throw new ApiException(BizError.STATE_409, "贵司已被平台停用，不能新建项目/导入批次（在催案件与结算不受影响）");
        }
    }
}
