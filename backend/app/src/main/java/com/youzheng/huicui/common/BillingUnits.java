package com.youzheng.huicui.common;

/**
 * 计费类型的三个纯函数事实（v1.19.0 唯一真源，替代散落各处的复刻）：
 *   ① 单位（契约 BillingUsage.unit 描述「分钟/条/次/件」）——此前 seed 写 'min'/'count'、
 *      BillingController 写 '分钟'、ReportsM10Controller 另有一份 unitOf()，三处漂移，V932 已归一。
 *   ② 预付/后付：STT/SMS 预付（余额不足必须拒 BIZ_QUOTA_EXHAUSTED）；EVIDENCE/LEGAL 后付
 *      （BR-M9-10 按次计入对账·不预充；允许负余额=欠用记账，见 org_balance 无 CHECK(balance>=0)）。
 *   ③ org×type 充值矩阵（BR-M9-07/08/10）：SMS 仅物业；STT 物业/服务商；平台自身不作充值受体。
 *      经 GET /billing/orgs 的 rechargeable 字段下发前端，**前端不再复刻这套规则**。
 */
public final class BillingUnits {

    public static final String STT = "STT";
    public static final String SMS = "SMS";
    public static final String EVIDENCE = "EVIDENCE";
    public static final String LEGAL = "LEGAL";

    public static final String[] ALL = {STT, SMS, EVIDENCE, LEGAL};

    private static final String ORG_PROPERTY = "PROPERTY";
    private static final String ORG_PROVIDER = "PROVIDER";

    private BillingUnits() {}

    /** 展示单位（契约口径中文）：STT=分钟 / SMS=条 / LEGAL=件 / EVIDENCE=次。 */
    public static String of(String type) {
        if (STT.equals(type)) return "分钟";
        if (SMS.equals(type)) return "条";
        if (LEGAL.equals(type)) return "件";
        return "次";
    }

    /** 预付项（余额不足必须拒绝）。EVIDENCE/LEGAL 后付费，允许透支。 */
    public static boolean isPrepaid(String type) {
        return STT.equals(type) || SMS.equals(type);
    }

    /** 该 org 类型能否充值该额度类型（充值矩阵）。平台自身一律 false。 */
    public static boolean rechargeable(String orgType, String type) {
        if (SMS.equals(type)) return ORG_PROPERTY.equals(orgType);
        if (STT.equals(type)) return ORG_PROPERTY.equals(orgType) || ORG_PROVIDER.equals(orgType);
        return false;   // EVIDENCE/LEGAL 不预充（RechargeTypeEnum 只有 STT/SMS）
    }

    /** 合法计费类型。 */
    public static boolean isValidType(String type) {
        return STT.equals(type) || SMS.equals(type) || EVIDENCE.equals(type) || LEGAL.equals(type);
    }
}
