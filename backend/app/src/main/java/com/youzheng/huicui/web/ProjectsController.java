package com.youzheng.huicui.web;

import com.youzheng.huicui.common.DataScope;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据范围演示端点（横切层验证用，非契约完整 Project 实现）：
 * GET /v1/projects-scope-demo —— 平台主体见全量，物业主体仅见本组织项目。
 * 证明 x-data-scope(own-org) 服务端强制裁剪 = 资金双线/三方隔离的同一机制。
 * 真 GET /projects 的契约完整实现（Project/ProjectForProvider 按角色）在 M2。
 */
@RestController
public class ProjectsController {

    private final JdbcTemplate jdbc;

    public ProjectsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ProjectLite(String id, String name, String orgName, String status) {}

    @GetMapping("/projects-scope-demo")
    @RequirePermission("proj.edit")
    public Map<String, Object> list() {
        CurrentSubject s = SubjectContext.get();
        DataScope.Fragment f = DataScope.ownOrg(s, "p.org_id");
        String sql = "SELECT p.id, p.name, p.org_name, p.status FROM project p WHERE 1=1" + f.sql() + " ORDER BY p.id";
        List<ProjectLite> items = jdbc.query(sql, (rs, i) ->
                new ProjectLite(String.valueOf(rs.getLong("id")), rs.getString("name"),
                        rs.getString("org_name"), rs.getString("status")), f.params());
        return Map.of("scopeApplied", s.isPlatform() ? "platform(全量)" : "own-org(orgId=" + s.orgId() + ")",
                "items", items, "meta", Map.of("total", items.size()));
    }
}
