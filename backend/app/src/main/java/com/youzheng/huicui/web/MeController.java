package com.youzheng.huicui.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /v1/me —— 当前主体（契约 operationId getMe，响应 schema Me）。
 * 地基期：从迁移好的 PG（account JOIN org，dev 种子 V900）读取一个主体，返回**契约合规**的 Me。
 * 真实鉴权（bearerAuth/JWT → 解析当前主体、401）属横切层，后续叠加；本步先证"可运行 + 连 PG + 响应合规"。
 */
@RestController
public class MeController {

    private final JdbcTemplate jdbc;

    public MeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 契约 Me schema：{ accountId, name, org{id,type,name}, role, dataScope?(null), permissions[] }
    public record OrgRef(String id, String type, String name) {}
    public record Me(String accountId, String name, OrgRef org, String role,
                     Object dataScope, List<String> permissions) {}

    @GetMapping("/me")
    public Me getMe() {
        return jdbc.queryForObject(
            "SELECT a.id AS aid, a.name AS aname, a.role_template AS role, " +
            "       o.id AS oid, o.type AS otype, o.name AS oname " +
            "FROM account a JOIN org o ON a.org_id = o.id ORDER BY a.id LIMIT 1",
            (rs, i) -> new Me(
                String.valueOf(rs.getLong("aid")),
                rs.getString("aname"),
                new OrgRef(String.valueOf(rs.getLong("oid")), rs.getString("otype"), rs.getString("oname")),
                rs.getString("role"),
                null,  // SA/平台：dataScope=null（契约 dataScope 为 nullable，平台见全量）
                List.of("proj.edit", "batch.import", "case.dispatch", "payreq.complete", "qc.review")
            ));
    }
}
