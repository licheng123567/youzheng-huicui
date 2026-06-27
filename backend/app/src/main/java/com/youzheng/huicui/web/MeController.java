package com.youzheng.huicui.web;

import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GET /v1/me —— 当前主体（契约 getMe → Me）。
 * 横切层落地后：主体来自 JWT（SubjectContext），无令牌已被 JwtAuthFilter 拦为 401。
 */
@RestController
public class MeController {

    public record OrgRef(String id, String type, String name) {}
    public record Me(String accountId, String name, OrgRef org, String role,
                     Object dataScope, List<String> permissions) {}

    @GetMapping("/me")
    public Me getMe() {
        CurrentSubject s = SubjectContext.get();
        return new Me(
                s.accountId(), s.name(),
                new OrgRef(s.orgId(), s.orgType(), s.orgName()),
                s.role(),
                dataScopeOf(s),
                s.permissions().stream().sorted().toList());
    }

    /**
     * dataScope（契约 Me.dataScope）：
     *   - SE（平台员工）→ 真实 data_range 三维 {areas,properties,providers}（BR-M1-14）。
     *   - SA（平台超管）→ null（全量不限）。
     *   - 非平台 → null（org 维度由 org 隔离表达，不走三维 data_range）。
     */
    private static Object dataScopeOf(CurrentSubject s) {
        if (!s.isSE()) return null;
        com.youzheng.huicui.security.DataRange r = s.dataRange();
        return Map.of(
                "areas", r.areas(),
                "properties", r.properties().stream().map(String::valueOf).toList(),
                "providers", r.providers().stream().map(String::valueOf).toList());
    }

    /** PATCH /v1/me —— 自助改密/换手机（契约 updateMe → 204）。骨架：校验通过即 204，真逻辑后续。 */
    @PatchMapping("/me")
    public ResponseEntity<Void> updateMe(@RequestBody(required = false) Map<String, Object> body) {
        SubjectContext.get();   // 须已认证
        // TODO: 校验 oldPassword/smsCode → 更新 newPassword(BCrypt)/newPhone；异常走 422。
        return ResponseEntity.noContent().build();
    }
}

