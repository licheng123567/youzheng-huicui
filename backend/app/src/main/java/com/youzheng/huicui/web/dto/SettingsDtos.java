package com.youzheng.huicui.web.dto;

/**
 * settings 组 DTO 集（对齐 openapi-core.yaml schema：Settings / SettingsInput /
 * SmsSendRecord）。带 Settings 前缀避免与 M1-M10 既有 DTO 冲突。
 *
 * 真值源：docs/api/openapi-core.yaml
 *   Settings{domain,version,effectiveAt,timers?,rotation?,markCodes?,closeReasons?,sms?}
 *   SettingsInput{domain(required),effectiveAt?,timers?,rotation?,markCodes?,closeReasons?,sms?}
 *   SmsSendRecord{id,caseId?,projectId?,template?,status,failureReason?,sentAt}
 *
 * 各域配置为半结构化 JSONB：读端以 Object（Jackson 树）透传，结构忠实落库内容；
 * 写端同样以 Object 收原始 JSON，按域映射到对应 JSONB 列——这样 AI 域（契约 enum 当前无 AI，
 * V910 规划）可前向兼容地以 enum 外 domain 落 JSONB（本批仅做白名单 + 前向兼容校验，不改 enum/约束）。
 *
 * 分页响应统一走 common.Page（{items,meta{page,size,total}}），故 SmsSendRecordPage 不单列。
 */
public final class SettingsDtos {
    private SettingsDtos() {}

    /**
     * 契约 Settings（GET /settings 裸数组项 / PUT /settings 出参）。
     * version=int；effectiveAt=date-time；各域为 object/array，JSONB→Jackson 树，缺省 null。
     * markCodes/closeReasons 为数组域，timers/rotation/sms 为对象域——统一用 Object 透传保结构。
     */
    public record SettingsDto(
            String domain,
            int version,
            String effectiveAt,
            Object timers,
            Object rotation,
            Object markCodes,
            Object closeReasons,
            Object sms) {}

    /**
     * 契约 SettingsInput（PUT /settings 入参）。domain required（缺/非法→422）。
     * effectiveAt 可空（空=立即生效，落库 COALESCE 到 now()）。
     * 各域可空 Object：仅按 domain 对应的那一列写入对应 JSONB（其余列 null，保持单域单列语义）。
     */
    public record SettingsInputDto(
            String domain,
            String effectiveAt,
            Object timers,
            Object rotation,
            Object markCodes,
            Object closeReasons,
            Object sms) {}

    /**
     * 契约 SmsSendRecord（GET /sms-records 列表项）。
     * caseId/projectId/template/failureReason 可空；status=SmsSendStatusEnum；sentAt=date-time。
     * FAILED 行带 failureReason（失败不退条数 BR-M9-08）。
     */
    public record SmsSendRecordDto(
            String id,
            String caseId,
            String projectId,
            String template,
            String status,
            String failureReason,
            String sentAt) {}
}
