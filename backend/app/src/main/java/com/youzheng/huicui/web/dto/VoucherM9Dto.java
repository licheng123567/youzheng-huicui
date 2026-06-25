package com.youzheng.huicui.web.dto;

/**
 * 凭证 DTO（对齐契约 components.schemas.Voucher）。M9 组1。
 * type←voucher.type(RECEIPT 收款凭证/收佣线 · PAYMENT 支付凭证/付佣线),
 * fileUrl←voucher.file_url, uploadedBy←voucher.uploaded_by（服务端派生·不接受前端传 ERD VOUCHER）,
 * uploadedAt←voucher.uploaded_at。
 */
public record VoucherM9Dto(
        String type,
        String fileUrl,
        String uploadedBy,
        String uploadedAt
) {}
