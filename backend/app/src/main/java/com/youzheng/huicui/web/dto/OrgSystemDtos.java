package com.youzheng.huicui.web.dto;

/**
 * orgs-system 组 DTO 集（对齐 openapi-core.yaml schema：Org / OrgInput / OwnerRebindInput /
 * AuditLog / PermissionMatrixItem）。带 OrgSystem 前缀避免与 M1-M10 既有 DTO 冲突。
 *
 * 真值源：docs/api/openapi-core.yaml
 *   Org{id,type,name,ownerAccountId,status}
 *   OrgInput{type,name,ownerAccount,ownerPhone}（均 required）
 *   OwnerRebindInput{newPhone(required),resetPassword?}
 *   AuditLog{id,actor,action,target,targetType,targetId,scope,proxyFor,before,after,reason,ip,traceId,tm}
 *   PermissionMatrixItem{feature,role,permission,dataScope,allowed}
 *
 * 分页响应统一走 common.Page（{items,meta{page,size,total}}），故不单列 *Page 记录。
 */
public final class OrgSystemDtos {
    private OrgSystemDtos() {}

    /** 契约 Org。type=OrgTypeEnum(PLATFORM/PROPERTY/PROVIDER)。id/ownerAccountId 为 string。 */
    public record OrgDto(
            String id,
            String type,
            String name,
            String ownerAccountId,
            String status) {}

    /** 契约 OrgInput（POST /orgs 入参，均 required）。 */
    public record OrgInputDto(
            String type,
            String name,
            String ownerAccount,
            String ownerPhone) {}

    /** 契约 OwnerRebindInput（PATCH /orgs/{id}/owner 入参；newPhone required）。 */
    public record OwnerRebindInputDto(
            String newPhone,
            Boolean resetPassword) {}

    /** 契约 AuditLog（GET /audit-log 列表项）。before/after 为 object（JSONB→Jackson 树），null→null。 */
    public record AuditLogDto(
            String id,
            String actor,
            String action,
            String target,
            String targetType,
            String targetId,
            String scope,
            String proxyFor,
            Object before,
            Object after,
            String reason,
            String ip,
            String traceId,
            String tm) {}

    /** 契约 PermissionMatrixItem（GET /permission-matrix 裸数组项）。 */
    public record PermissionMatrixItemDto(
            String feature,
            String role,
            String permission,
            String dataScope,
            boolean allowed) {}
}
