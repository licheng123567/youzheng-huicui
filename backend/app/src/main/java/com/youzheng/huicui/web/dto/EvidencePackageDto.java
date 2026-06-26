package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 案件存证打包下载 DTO（对齐契约 components.schemas.EvidencePackage）。M6 evidence 组。
 * 字段与契约 1:1：caseId/documentUrl/itemCount/items。
 *   caseId      ← 路径案件 id；
 *   documentUrl ← 打包文件下载 url（文件通道 TBD·占位，地基期恒 null）；
 *   itemCount   ← 打包内已出证(ISSUED)存证条数；
 *   items       ← 已出证存证条目列表（EvidenceItemDto，三方隔离裁剪后）。
 * 占位语义：聚合本案 status=ISSUED 的存证，文件实际打包通道未定故 documentUrl 占位 null。
 */
public record EvidencePackageDto(
        String caseId,
        String documentUrl,
        int itemCount,
        List<EvidenceItemDto> items
) {}
