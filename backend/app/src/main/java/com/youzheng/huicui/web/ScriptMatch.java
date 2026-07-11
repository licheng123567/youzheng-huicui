package com.youzheng.huicui.web;

/**
 * 案件事实 → cohort(人群) / scene(场景) 的**确定性**派生（飞轮环2 通话前推荐 + 环3 承诺回退归因共用）。
 *
 * 不依赖 LLM/RAG——用可解释的规则从案件画像派生分层键，据此从话术库按 cohort/scene 匹配。
 * 真语义匹配（embedding 向量检索）是后置接缝（script_lib.embedding 仍死列），此处先落确定性规则。
 */
public final class ScriptMatch {

    private ScriptMatch() {}

    /** cohort 人群分层：欠费账龄/金额/可达性/态度。 */
    public static String cohort(int arrearMonths, long dueCents, int callCount,
                                boolean hasContact, boolean lastRefused) {
        if (callCount == 0 && !hasContact) return "失联户";
        if (lastRefused) return "钉子户";
        if (arrearMonths >= 12) return "长期欠费";
        if (dueCents >= 1_000_000L) return "高额欠费";   // ≥¥10000
        return "一般欠费";
    }

    /** scene 场景：由接触阶段 + 承诺状态派生。 */
    public static String scene(int callCount, boolean hasContact,
                               int brokenPromises, boolean hasPendingPromise) {
        if (callCount == 0) return hasContact ? "首催开场" : "失联破冰";
        if (hasPendingPromise) return "分期引导";
        if (brokenPromises > 0) return "催缴施压";
        return "首催开场";
    }
}
