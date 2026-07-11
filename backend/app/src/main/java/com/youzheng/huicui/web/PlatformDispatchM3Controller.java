package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【已停用 v1.18.0】开放抢单（open-for-claim）业务下线——端点保留为停用桩（恒 409），路由/契约不破坏。
 *
 * 停用背景（2026-07-11 用户拍板，系统未上线无存量）：开放池让催收员个人跨商抢单、却由其服务商
 * 承担佣金结算与质检责任——个体行为产生组织义务，与全系统「组织对组织」的商业模型断裂；且
 * 「服务商执行不佳」场景已被 批次结项→重派（v1.17.0 batch_engagement）闭环覆盖。
 * 催收员抢单仅保留本服务商公海（S2，组织内工作分配，不产生新商业义务）。
 * 存量 S4 案件由 V931 迁回平台公海 S0；鉴权口径（perm+platform）保持不放宽。
 */
@RestController
public class PlatformDispatchM3Controller {

    @PostMapping("/cases/{id}/open-for-claim")
    @RequirePermission("case.dispatch")
    public Map<String, Object> openCaseForClaim(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        // scope=platform：非平台主体即便有权限点也越权拒（停用桩鉴权口径不放宽）。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台运营/超管可操作");
        }
        parseId(id);                                           // 非法形态 → 404（口径不变）
        throw new ApiException(BizError.STATE_409,
                "开放抢单已停用(v1.18.0)：服务商执行不佳请走批次「结项→重派」；催收员抢单仅限本服务商公海");
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        }
    }
}
