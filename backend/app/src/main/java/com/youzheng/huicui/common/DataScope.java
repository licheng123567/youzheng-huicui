package com.youzheng.huicui.common;

import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.DataRange;

import java.util.List;

/**
 * x-data-scope 数据范围助手：按当前主体生成 SQL 过滤片段，服务端强制裁剪。
 *
 * 词表：platform(全量) / own-org(本组织) / range(SE 区域·物业·服务商三维) /
 *       case-holder(本案持有催收员) / case-actor(持有 CO + 关联 PL/PC + SA 代) / public。
 *
 * 【range 三方+细分隔离】（issue#3 B-01/B-02 统一收口）——平台/物业/服务商三分支按角色细分：
 *   - SA（PLATFORM/超管）  → 全量不限。
 *   - SE（PLATFORM/员工）  → 按 account.data_range 三维裁剪（areas/properties/providers，BR-M1-14；空维度=不限）。
 *   - PROVIDER（VL/CO）    → 案件级 provider_id = 本组织（唯一权威，不回落 batch）。
 *   - PL（PROPERTY/负责人）→ project.org_id = 本组织（看本物业全量）。
 *   - PC（PROPERTY/协调员）→ project.org_id = 本组织 AND 案件 project_id/batch_id ∈ 本人协调集（行级隔离）。
 *
 * 各调用点传入本表查询使用的列表达式（别名随 SQL 而异），由本助手拼出统一片段。
 */
public final class DataScope {
    private DataScope() {}

    /** own-org：平台主体不限，其余限本组织。返回追加到 WHERE 的片段（含前导 AND）与参数。 */
    public static Fragment ownOrg(CurrentSubject s, String orgIdColumn) {
        if (s.isPlatform()) return Fragment.NONE;            // 平台见全量
        return new Fragment(" AND " + orgIdColumn + " = ?", new Object[]{ Long.valueOf(s.orgId()) });
    }

    /**
     * range 三方+细分隔离的统一拼接。直接 append 到 WHERE（含前导 AND）+ 追加参数。
     *
     * @param providerCol  服务商裁剪列表达式（如 "c.provider_id" 或 "b.provider_id"）
     * @param projectOrgCol 物业归属列表达式（如 "p.org_id"）
     * @param projectAreaCol 项目区域列表达式（如 "p.area"），SE areas 维用；null=不支持区域维（该维退化为不裁剪）
     * @param caseProjectIdCol PC 协调判定的项目 id 列（如 "c.project_id"）
     * @param caseBatchIdCol   PC 协调判定的批次 id 列（如 "c.batch_id"）
     */
    public static void appendRange(CurrentSubject s, StringBuilder where, List<Object> args,
                                   String providerCol, String projectOrgCol, String projectAreaCol,
                                   String caseProjectIdCol, String caseBatchIdCol) {
        if (s.isPlatform()) {
            // SA 超管全量；SE 按 data_range 三维裁剪。
            if (!s.isSE()) return;                            // SA（及其它平台角色）全量不限
            appendSeDataRange(s.dataRange(), where, args, providerCol, projectOrgCol, projectAreaCol);
            return;
        }
        if ("PROVIDER".equals(s.orgType())) {                 // 服务商：案件级 provider_id 唯一权威（不回落 batch）
            where.append(" AND ").append(providerCol).append(" = ?");
            args.add(orgId(s));
            return;
        }
        // PROPERTY：PL 看本物业全量；PC 行级隔离（B-02）。
        where.append(" AND ").append(projectOrgCol).append(" = ?");
        args.add(orgId(s));
        if (s.isPC()) {
            appendPcCoordinator(s, where, args, caseProjectIdCol, caseBatchIdCol);
        }
    }

    /** SE 三维 data_range 裁剪（空维度=不限；非空维度 AND；UNRESTRICTED 不追加任何片段）。 */
    private static void appendSeDataRange(DataRange r, StringBuilder where, List<Object> args,
                                          String providerCol, String projectOrgCol, String projectAreaCol) {
        if (r == null || r.isUnrestricted()) return;          // 默认=全平台不限
        if (r.isRestrictedEmpty()) { where.append(" AND 1=0"); return; }  // fail-closed：非法 data_range → 空集（绝不放大）
        if (r.hasProviders() && providerCol != null) {
            where.append(" AND ").append(providerCol).append(" IN (").append(qs(r.providers().size())).append(')');
            args.addAll(r.providers());
        }
        if (r.hasProperties() && projectOrgCol != null) {
            where.append(" AND ").append(projectOrgCol).append(" IN (").append(qs(r.properties().size())).append(')');
            args.addAll(r.properties());
        }
        if (r.hasAreas() && projectAreaCol != null) {
            where.append(" AND ").append(projectAreaCol).append(" IN (").append(qs(r.areas().size())).append(')');
            args.addAll(r.areas());
        }
    }

    /**
     * PC 协调员行级协调集片段（B-02），供调用点在已自行限定物业 org 后单独叠加（如 Evidence 用 e.org_id）。
     * 仅当主体确为 PC 时调用；非 PC 调用者不应进入。
     */
    public static void appendPcCoordinatorSet(CurrentSubject s, StringBuilder where, List<Object> args,
                                              String caseProjectIdCol, String caseBatchIdCol) {
        appendPcCoordinator(s, where, args, caseProjectIdCol, caseBatchIdCol);
    }

    /** PC 协调员行级：案件 project_id 或 batch_id 命中本人协调集（B-02）。 */
    private static void appendPcCoordinator(CurrentSubject s, StringBuilder where, List<Object> args,
                                            String caseProjectIdCol, String caseBatchIdCol) {
        Long acct = parseLong(s.accountId());
        if (acct == null) { where.append(" AND 1=0"); return; }   // 无主体 id → 空集（防越权）
        where.append(" AND (")
             .append(caseProjectIdCol).append(" IN (SELECT project_id FROM project_coordinators WHERE coordinator_id = ?)")
             .append(" OR ")
             .append(caseBatchIdCol).append(" IN (SELECT batch_id FROM batch_coordinators WHERE coordinator_id = ?))");
        args.add(acct);
        args.add(acct);
    }

    /**
     * SE 单对象可见性判定（用于 pass/throw 式 require* 守门，非列表裁剪）。
     * 任一维度非空且该对象不在集合内 → 不可见。维度参数为 null = 该对象不参与该维判定（视为通过）。
     * SA / 非 SE 平台 / data_range UNRESTRICTED → 恒可见。
     */
    public static boolean seVisible(CurrentSubject s, Long projectOrgId, String projectArea, Long providerId) {
        if (!s.isSE()) return true;                       // 仅 SE 受三维约束；SA 全量
        DataRange r = s.dataRange();
        if (r != null && r.isRestrictedEmpty()) return false;  // fail-closed：非法 data_range → 恒不可见
        if (r == null || r.isUnrestricted()) return true;
        if (r.hasProperties() && projectOrgId != null && !r.properties().contains(projectOrgId)) return false;
        if (r.hasAreas() && projectArea != null && !r.areas().contains(projectArea)) return false;
        if (r.hasProviders() && providerId != null && !r.providers().contains(providerId)) return false;
        return true;
    }

    private static Long orgId(CurrentSubject s) { return Long.valueOf(s.orgId()); }

    private static Long parseLong(String v) {
        try { return v == null ? null : Long.valueOf(v); } catch (RuntimeException e) { return null; }
    }

    /** n 个 "?" 逗号分隔（n>=1）。 */
    private static String qs(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) b.append(','); b.append('?'); }
        return b.toString();
    }

    public record Fragment(String sql, Object[] params) {
        public static final Fragment NONE = new Fragment("", new Object[0]);
    }
}
