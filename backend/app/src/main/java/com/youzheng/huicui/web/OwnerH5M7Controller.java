package com.youzheng.huicui.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.web.dto.InstallmentDto;
import com.youzheng.huicui.web.dto.OwnerBillDto;
import com.youzheng.huicui.web.dto.PayChannelsDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M7 业主缴费 H5 读端点（owner-h5·无登录·凭 token 只读）。
 * 类名带 M7 后缀，承载本组 public 端点；只读不写。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，方法注解写裸路径）：
 *   GET /pay/{token}  getOwnerBill —— 业主缴费账单，x-data-scope=public。
 *
 * **public 免鉴权**：契约 security:[]；JwtAuthFilter.isPublic 已对 path.startsWith("/pay/") 放行。
 *   本端点不加 @RequirePermission、不读 SubjectContext、不依赖 CurrentSubject。
 *
 * 链接失效/作废 BR-M7-04：token 不存在 → 404；pl.status='EXPIRED' 或 expires_at<now() → 404（提示失效，
 *   不返账单数据）。绝不 5xx。
 *
 * 隐私最小化 BR-M7-07：仅返缴费必要信息，不读取/不返回催收过程/timeline/他案/服务商/holder/org 任何字段；
 *   public 不泄越权数据——只暴露该 token 对应单案账单（按 token 定位单条 + 单 case_id）。
 *
 * 金额：*_cents 列原样以「分」(Long) 返回，对齐契约 Money=integer 分，不转元。
 */
@RestController
public class OwnerH5M7Controller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OwnerH5M7Controller(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ── [1] GET /pay/{token} （public 免鉴权） ────────────────────────────────
    @GetMapping("/pay/{token}")
    public OwnerBillDto getOwnerBill(@PathVariable String token) {
        // 仅按 token 定位单条 pay_link + 其单一 case_id（隐私最小化：只取缴费必要列，
        // 不 SELECT 催收过程/holder/org 等越权字段）。token 不存在 → 404（不 5xx）。
        List<OwnerBillRow> rows = jdbc.query(
                "SELECT pl.status AS pl_status,"
                        + " (pl.expires_at < now()) AS pl_expired,"
                        + " c.project_id AS project_id,"
                        + " c.project_name AS community,"
                        + " c.due_cents AS due_cents,"
                        + " c.reduce_after_cents AS reduce_after_cents,"
                        + " c.arrearags_periods AS arrearags_periods"
                        + " FROM pay_link pl"
                        + " JOIN \"case\" c ON c.id = pl.case_id"
                        + " WHERE pl.token = ?",
                (rs, i) -> {
                    OwnerBillRow r = new OwnerBillRow();
                    r.plStatus = rs.getString("pl_status");
                    r.plExpired = rs.getBoolean("pl_expired");
                    r.projectId = rs.getLong("project_id");
                    r.community = rs.getString("community");
                    r.dueCents = rs.getLong("due_cents");
                    long ra = rs.getLong("reduce_after_cents");
                    r.reduceAfterCents = rs.wasNull() ? null : ra;
                    r.arrearagsPeriods = rs.getString("arrearags_periods");
                    return r;
                },
                token);

        if (rows.isEmpty()) {
            // token 不存在 → 404，不返账单数据（BR-M7-04），绝不 5xx。
            throw new ApiException(BizError.NOT_FOUND_404, "链接已失效或不存在");
        }
        OwnerBillRow r = rows.get(0);
        // 作废/过期：状态 EXPIRED 或已过期 → 404（提示失效，不返账单数据）。
        if ("EXPIRED".equals(r.plStatus) || r.plExpired) {
            throw new ApiException(BizError.NOT_FOUND_404, "链接已失效");
        }

        // 减免后应收（payable）= reduce_after_cents ?: due_cents；减免额 = due − payable。
        long payableCents = r.reduceAfterCents != null ? r.reduceAfterCents : r.dueCents;
        long reductionCents = r.dueCents - payableCents;

        // 项目维度：收费标准摘要 + 收款渠道（按 project_id 单独取，仍不触碰催收过程字段）。
        String feeStd = null;
        PayChannelsDto payChannels = new PayChannelsDto(null, null);
        List<ProjPayRow> projRows = jdbc.query(
                "SELECT fee_rows, pay_info FROM project WHERE id = ?",
                (rs, i) -> {
                    ProjPayRow p = new ProjPayRow();
                    p.feeRows = rs.getString("fee_rows");
                    p.payInfo = rs.getString("pay_info");
                    return p;
                },
                r.projectId);
        if (!projRows.isEmpty()) {
            feeStd = summarizeFeeRows(projRows.get(0).feeRows);
            payChannels = parsePayChannels(projRows.get(0).payInfo);
        }

        List<String> arrearagePeriods = parseStringArray(r.arrearagsPeriods);

        // 政策分期 BR-M7-06：承诺分期 ≠ 政策分期；地基期返 null（留 TODO 接政策/减免规则分期）。
        List<InstallmentDto> installments = null;

        // onlinePay 恒 false（本期线下缴·BR-M7-05）。
        return new OwnerBillDto(
                r.community,
                payableCents,
                reductionCents,
                feeStd,
                arrearagePeriods,
                installments,
                payChannels,
                false);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** jsonb 文本 → List<String>（arrearags_periods）。空/异常返回空列表，绝不抛 5xx。 */
    private List<String> parseStringArray(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** project.pay_info（TEXT 存 JSON）→ {wechatQr,bankAccount}。无/异常返回两字段 null。 */
    private PayChannelsDto parsePayChannels(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return new PayChannelsDto(null, null);
        try {
            Map<String, Object> m = json.readValue(jsonText, new TypeReference<Map<String, Object>>() {});
            Object wq = m.get("wechatQr");
            Object ba = m.get("bankAccount");
            return new PayChannelsDto(
                    wq == null ? null : String.valueOf(wq),
                    ba == null ? null : String.valueOf(ba));
        } catch (Exception e) {
            return new PayChannelsDto(null, null);
        }
    }

    /** fee_rows jsonb [{biz,std}] → 展示串 "物业费:1.5元/㎡·月; 停车费:..."（同 CasesM2.summarizeFeeRows）。无则 null。 */
    private String summarizeFeeRows(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return null;
        try {
            List<Map<String, Object>> rows =
                    json.readValue(jsonText, new TypeReference<List<Map<String, Object>>>() {});
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Object biz = r.get("biz");
                Object std = r.get("std");
                if (biz == null && std == null) continue;
                parts.add((biz == null ? "" : biz) + ":" + (std == null ? "" : std));
            }
            return parts.isEmpty() ? null : String.join("; ", parts);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 行载体（仅缴费必要列；不承载催收过程/holder/org 字段，隐私最小化 BR-M7-07）──
    private static final class OwnerBillRow {
        String plStatus;
        boolean plExpired;
        long projectId;
        String community;
        long dueCents;
        Long reduceAfterCents;
        String arrearagsPeriods;
    }

    private static final class ProjPayRow {
        String feeRows;
        String payInfo;
    }
}
