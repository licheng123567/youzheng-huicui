package com.youzheng.huicui.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 契约 MySettlement（催收员"我的结算/佣金"自查·只读 BR-M9-19a）。
 * 比例/已支付由服务商设定，催收员只读。
 * rows 按（结算周期=回款到账月, 批次, 是否已结）聚合（对标原型§我的结算表：结算周期/项目/批次/回款/比例/提成/状态）。
 * rows[].rate 为 Rate=NUMERIC(6,4) 分数（0-1），DB/后端/前端一致不×100。
 */
public record MySettlementM9Dto(
        Long totalCents,
        Long settledCents,
        Long unsettledCents,
        List<Row> rows) {

    public record Row(
            String period,
            String project,
            String batch,
            Long repayCents,
            BigDecimal rate,
            Long commCents,
            Boolean settled) {}
}
