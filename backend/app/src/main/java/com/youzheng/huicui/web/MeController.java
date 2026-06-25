package com.youzheng.huicui.web;

import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
                s.isPlatform() ? null : java.util.Map.of("areas", List.of(), "properties", List.of(), "providers", List.of()),
                s.permissions().stream().sorted().toList());
    }
}
