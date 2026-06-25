package com.youzheng.huicui.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.dispatch.CaseStateService;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.SeaCaseDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * M3 公海读端点（sea 组）。只读，无状态转移。
 * 类名带 M3 后缀，避免与 M1/M2 既有 controller 冲突；只承载 GET /sea。
 *
 * 端点（基路径 /v1 由 context-path 提供）：
 *   GET /sea?pool={platform|provider|open}  listSea —— SeaCasePage（契约 /sea）。
 *
 * 契约口径（openapi-core.yaml /sea + SeaCase + scaffold sea 组规范）：
 *   - 无 x-permission（靠 x-data-scope=range 控可见性）。
 *   - pool 必填，pool→物理池映射：platform→PLATFORM_SEA, provider→PROVIDER_SEA, open→OPEN_POOL。
 *   - scope=range 三分支裁剪（复用 CasesM2Controller.appendRangeScope 范式，JOIN project/batch）：
 *       platform 池：仅平台主体可见全量；非平台→空集（range 空裁剪即可，不抛 403）。
 *       provider 池：服务商主体见 b.provider_id=本商 的 PROVIDER_SEA 案件；平台见全量；其他→空集。
 *       open   池：OPEN_POOL 对全平台 CO 开放（BR-M3-15 不限 provider 归属），仅返回 holder_id IS NULL 可抢案件。
 *   - SeaCase 视图字段：viewerCount(0 占位)、sourceBadge(case.pool 派生)、
 *       competitionState(holder 非空→CLAIMED 否则 AVAILABLE；VIEWING 实时态后续接 SSE)、
 *       contactMasked(公海未持有=true)、eventCursor(null 占位)、capacityHint(CO 主体=余量否则 null)。
 *   - 脱敏 BR-M3-21a：公海未持有案件 contactMasked=true，ownerName 脱敏占位、phone 不下发。
 *
 * 健壮性（绝不 5xx）：pool 缺失/非法 → 422；page/size 越界由 Pageable 规整；空集合法返回 200 空页。
 */
@RestController
public class SeaM3Controller {

    private final JdbcTemplate jdbc;
    private final CaseStateService caseState;
    private final ObjectMapper json;

    public SeaM3Controller(JdbcTemplate jdbc, CaseStateService caseState, ObjectMapper json) {
        this.jdbc = jdbc;
        this.caseState = caseState;
        this.json = json;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String REDACTED_NAME = "***";

    @GetMapping("/sea")
    public Page<SeaCaseDto> listSea(
            @RequestParam(required = false) String pool,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        // pool 必填 + 枚举校验（缺/非法 → 422，绝不 5xx）。
        String physicalPool = mapPool(pool);

        StringBuilder where = new StringBuilder(" WHERE c.pool = ?");
        List<Object> args = new ArrayList<>();
        args.add(physicalPool);

        // open 池仅返回可抢（holder 为空）案件（BR-M3-15）。
        if (CaseStateService.POOL_OPEN_POOL.equals(physicalPool)) {
            where.append(" AND c.holder_id IS NULL");
        }

        // scope=range 裁剪（按池 + 主体分支；不可见 → 空集而非 403）。
        if (!appendSeaScope(s, physicalPool, where, args)) {
            // 该主体对该池不可见：合法返回空页（不泄露存在性，不抛 403）。
            return Page.of(List.of(), pg, 0);
        }

        String base = "FROM \"case\" c"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<SeaCaseDto> items = jdbc.query(
                "SELECT c.* " + base + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
                seaRowMapper(s), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** pool 查询参数 → 物理池；缺失/非法 → 422。 */
    private static String mapPool(String pool) {
        if (pool == null || pool.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少必填查询参数 pool");
        }
        return switch (pool) {
            case "platform" -> CaseStateService.POOL_PLATFORM_SEA;
            case "provider" -> CaseStateService.POOL_PROVIDER_SEA;
            case "open" -> CaseStateService.POOL_OPEN_POOL;
            default -> throw new ApiException(BizError.VALIDATION_422,
                    "非法 pool 取值（须为 platform/provider/open）: " + pool);
        };
    }

    /**
     * scope=range 公海裁剪：按物理池追加 WHERE（含前导 AND）。
     * 返回 false 表示该主体对该池整体不可见（调用方直接返回空页）。
     */
    private boolean appendSeaScope(CurrentSubject s, String physicalPool,
                                   StringBuilder where, List<Object> args) {
        switch (physicalPool) {
            case CaseStateService.POOL_PLATFORM_SEA -> {
                // 平台公海：仅平台主体可见全量；非平台不可见。
                return s.isPlatform();
            }
            case CaseStateService.POOL_PROVIDER_SEA -> {
                // 服务商公海：平台见全量；服务商见本商；其余不可见。
                if (s.isPlatform()) return true;
                if ("PROVIDER".equals(s.orgType())) {
                    where.append(" AND b.provider_id = ?");
                    args.add(Long.valueOf(s.orgId()));
                    return true;
                }
                return false;
            }
            case CaseStateService.POOL_OPEN_POOL -> {
                // 开放池：全平台 CO 开放，不限 provider 归属（BR-M3-15）；平台亦可见。
                // 仅 CO / 平台主体可见（物业主体与抢单无关 → 不可见）。
                return s.isPlatform() || "PROVIDER".equals(s.orgType());
            }
            default -> {
                return false;
            }
        }
    }

    /** SeaCase RowMapper：Case 基字段 + 公海视图字段，公海未持有脱敏（BR-M3-21a）。 */
    private org.springframework.jdbc.core.RowMapper<SeaCaseDto> seaRowMapper(CurrentSubject s) {
        // capacityHint：仅 CO 主体（PROVIDER + role=CO）有意义 = CFG-HOLDCAP - 本人私海持有数。
        final Integer capacityHint = computeCapacityHint(s);
        return (rs, i) -> {
            Long holderId = (Long) rs.getObject("holder_id");
            String poolVal = rs.getString("pool");
            // 公海视图：是否本主体持有（CO 看自己持有的不脱敏；公海池里一般未持有）。
            boolean ownedByViewer = holderId != null
                    && holderId.toString().equals(s.accountId());
            boolean contactMasked = !ownedByViewer;   // 未持有一律脱敏（BR-M3-21a）
            String ownerName = contactMasked ? REDACTED_NAME : rs.getString("owner_name");

            String competitionState = holderId != null ? "CLAIMED" : "AVAILABLE";

            return new SeaCaseDto(
                    // ── allOf(Case) ──
                    String.valueOf(rs.getLong("id")),
                    rs.getString("acct_no"),
                    String.valueOf(rs.getLong("batch_id")),
                    String.valueOf(rs.getLong("project_id")),
                    rs.getString("project_name"),
                    ownerName,
                    rs.getString("room"),
                    longOrNull(rs, "due_cents"),
                    longOrNull(rs, "reduce_after_cents"),
                    parseStringArray(rs.getString("arrearags_periods")),
                    parseJsonObject(rs.getString("litigation_fields")),
                    rs.getString("status"),
                    rs.getString("legal_stage"),
                    holderId == null ? null : String.valueOf(holderId),
                    poolVal,
                    rs.getString("source"),
                    ts(rs.getTimestamp("t2_deadline")),
                    ts(rs.getTimestamp("t_collector_deadline")),
                    rs.getString("closed_kind"),
                    ts(rs.getTimestamp("closed_at")),
                    contactMasked,              // redacted 与 contactMasked 同口径（未持有脱敏）
                    // ── 公海竞争态增量字段 ──
                    0,                          // viewerCount：M3 占位 0（实时态后续接 SSE/轮询）
                    poolVal,                    // sourceBadge：入池来源徽标，取 case.pool 派生（PoolEnum）
                    competitionState,
                    contactMasked,
                    null,                       // eventCursor：M3 占位 null
                    capacityHint);
        };
    }

    /** capacityHint：CO 主体（PROVIDER 组织 + role=CO）= CFG-HOLDCAP - 本人私海持有数；非 CO 主体 null。 */
    private Integer computeCapacityHint(CurrentSubject s) {
        if (!"CO".equals(s.role())) return null;
        try {
            long coId = Long.parseLong(s.accountId());
            int remaining = caseState.holdCap() - caseState.holdCount(coId);
            return Math.max(remaining, 0);
        } catch (RuntimeException e) {
            return null;   // 任何派生异常退化为 null，绝不 5xx
        }
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    /** jsonb 文本 → List<String>（arrearags_periods）。空/异常返回空列表。 */
    private List<String> parseStringArray(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** jsonb 文本 → Object（litigation_fields；null 列保持 null）。 */
    private Object parseJsonObject(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return null;
        try {
            return json.readValue(jsonText, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
