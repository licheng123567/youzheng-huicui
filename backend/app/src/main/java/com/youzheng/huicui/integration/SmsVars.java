package com.youzheng.huicui.integration;

import java.util.Set;

/**
 * 短信模板变量白名单（v1.21.0）。模板报备时提交的 var_order 只能取这些键。
 *
 * 为什么要白名单 + var_order 入库（而不是"约定固定顺序 + 写文档"）：
 *   运营商侧模板是「{0} {1} {2}」的位置变量，顺序一旦与报备时不一致，就会把
 *   「张三，欠费 3600 元」发成「3600，欠费 张三 元」。文档不会在 CI 里失败，机器校验会。
 *   写侧（创建/编辑模板）校验 var_order ⊆ 白名单；报备生效（register→ACTIVE）时另校验
 *   var_order.size() == content 里 {n} 占位符个数。
 */
public final class SmsVars {

    public static final String PAY_URL = "payUrl";
    public static final String OWNER_NAME = "ownerName";
    public static final String AMOUNT = "amount";
    public static final String PROJECT_NAME = "projectName";
    public static final String ROOM = "room";
    public static final String DUE_DATE = "dueDate";

    public static final Set<String> ALLOWED = Set.of(
            PAY_URL, OWNER_NAME, AMOUNT, PROJECT_NAME, ROOM, DUE_DATE);

    private SmsVars() {}

    public static boolean isAllowed(String key) {
        return ALLOWED.contains(key);
    }
}
