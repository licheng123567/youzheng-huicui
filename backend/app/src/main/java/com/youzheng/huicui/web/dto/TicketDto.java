package com.youzheng.huicui.web.dto;

/**
 * 工单 DTO（对齐契约 components.schemas.Ticket）。
 * 列名映射：caseId←case_id, createdBy←created_by, createdAt←created_at,
 *           handledBy←handled_by, handledAt←handled_at。
 * status（ticket.chk_ticket_status，TicketStatusEnum）：PENDING/HANDLED。
 */
public record TicketDto(
        String id,
        String caseId,
        String type,
        String note,
        String status,
        String result,
        String receipt,
        String createdBy,
        String createdAt,
        String handledBy,
        String handledAt
) {}
