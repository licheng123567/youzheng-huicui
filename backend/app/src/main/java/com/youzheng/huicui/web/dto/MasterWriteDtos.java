package com.youzheng.huicui.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「master-write」组（导入批次/手工入案/作废/协调员/减免阶梯/项目级配置）写端点 DTO 集。
 * 对齐冻结契约 openapi-core.yaml 的 BatchImport / CaseImportRow / ManualCaseInput / LitigationFields /
 * ImportResult / ImportError / ReduceTier / CoordinatorsInput / ReasonInput schema。
 *
 * 约定：
 *   - 金额 *_cents 一律 Long（契约 Money=integer 分），原样不转元；
 *   - 比率 Rate=分数(0-1)，用 BigDecimal 直存 NUMERIC(6,4)（commInRate）；
 *   - reduce_tier.discount 是文本（如 "9折"），原样存取不当作百分比；
 *   - 类名带 MasterWrite 前缀的内嵌 record，避免与既有读端点 DTO（CaseDto/BatchPlatformView 等）冲突。
 */
public final class MasterWriteDtos {
    private MasterWriteDtos() {}

    // ── 导入批次 importBatch ──────────────────────────────────────────────
    /** 契约 BatchImport：projectId/commInRate(Rate 0-1)/rows[]。 */
    public record BatchImportInput(String projectId, BigDecimal commInRate, List<CaseImportRow> rows) {}

    /** 契约 CaseImportRow：必填户号/姓名/手机/房号/应收/欠费周期；诉讼要素可后补。 */
    public record CaseImportRow(
            String acctNo, String ownerName, String phone, String room,
            Long dueCents, String arrearPeriod, ImportLitigation litigation) {}

    /** 契约 CaseImportRow.litigation（{idCard,addr}）。 */
    public record ImportLitigation(String idCard, String addr) {}

    /** 契约 ImportResult：batch + 计数 + 逐行错误。batch 用既有 BatchPlatformView（平台视角双线）。 */
    public record ImportResult(
            Object batch, int total, int succeeded, int skipped, List<ImportError> errors) {}

    /** 契约 ImportError：{row,field,code,message}。 */
    public record ImportError(int row, String field, String code, String message) {}

    // ── 手工逐条入案 createCase ───────────────────────────────────────────
    /** 契约 ManualCaseInput：复用导入字段 + arrearagePeriods[] + 可选 LitigationFields。 */
    public record ManualCaseInput(
            String acctNo, String ownerName, String phone, String room,
            Long dueCents, List<String> arrearagePeriods, LitigationFields litigationFields) {}

    /** 契约 LitigationFields：{idCard,buildingArea,mailingAddr,contractNo}。 */
    public record LitigationFields(String idCard, String buildingArea, String mailingAddr, String contractNo) {}

    // ── 作废 voidBatch / voidCase ─────────────────────────────────────────
    /** 契约 ReasonInput：{reason}（必填）。 */
    public record ReasonInput(String reason) {}

    // ── 协调员全量覆盖 setBatchCoordinators / setProjectCoordinators ───────
    /** 契约 CoordinatorsInput：{coordinatorIds[]}（全量覆盖）。 */
    public record CoordinatorsInput(List<String> coordinatorIds) {}

    // ── 减免阶梯 ReduceTier（GET/PUT 共用）────────────────────────────────
    /** 契约 ReduceTier：discount(文本)/capCents(Money?)/waivePenalty/decide(枚举)。 */
    public record ReduceTierDto(String discount, Long capCents, Boolean waivePenalty, String decide) {}

    /** PUT /batches/{id}/reduce-tiers 入参：{tiers[]}（空数组=清除自定义恢复继承）。 */
    public record ReduceTiersPutInput(List<ReduceTierDto> tiers) {}

    /** GET/PUT /batches/{id}/reduce-tiers 出参：{source:INHERITED|CUSTOM, tiers[]}。 */
    public record ReduceTiersResult(String source, List<ReduceTierDto> tiers) {}
}
