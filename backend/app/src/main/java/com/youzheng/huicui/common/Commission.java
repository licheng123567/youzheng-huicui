package com.youzheng.huicui.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 佣金计算助手（BR-M9-01b/02·逐笔×比率·分数）。
 *
 * Rate 为 NUMERIC(6,4) 分数口径（v1.0.3，0-1，DB/后端/前端一致不×100）。
 * 基数口径=减免后实收·不含税（即 repay_line.amount_cents）。
 * 逐笔计算：commCents = round(repayCents × rate)，整数舍入用 BigDecimal.setScale(0, HALF_UP)。
 * 单 baseCents = Σ明细 repayCents；单 commCents = Σ明细 commCents（逐笔舍入后求和，避免汇总再舍入的偏差）。
 *
 * 与 PaymentRequest 组单/结算、CoCommission 催收员佣金汇总共用此口径。
 */
public final class Commission {
    private Commission() {}

    /** 单笔佣金（分）：round(repayCents × rate)，HALF_UP 到整数分。 */
    public static long lineCommissionCents(long repayCents, BigDecimal rate) {
        if (rate == null) throw new IllegalArgumentException("rate must not be null");
        return BigDecimal.valueOf(repayCents)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
