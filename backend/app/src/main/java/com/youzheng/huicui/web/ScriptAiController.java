package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youzheng.huicui.audit.AuditService;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.AiConfigDto;
import com.youzheng.huicui.web.dto.ScriptDto;
import com.youzheng.huicui.web.dto.ScriptInputDto;
import com.youzheng.huicui.web.dto.ScriptVariantDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 护城河 script-ai 组：话术库 + AI 配置中心（横切层范式同 CasesM2Controller，JdbcTemplate + ObjectMapper）。
 * 类名 ScriptAiController（web 包，避免碰 M1-M10/org-member 既有 controller）。基路径 /v1 由 context-path 提供。
 *
 * 端点（真值源 openapi-core.yaml ai tag + 本组规范 BR-M5-06/06a/12a/13）：
 *   GET  /script-lib                       listScripts          scope=platform           —— 话术库列表，Wilson 默认排序（飞轮第六环）。
 *   POST /script-lib                       createScript         perm=ai.config scope=platform —— 录入专家话术（候选·飞轮第一环）。
 *   POST /script-lib/{id}/variant/promote  promoteScriptVariant perm=ai.config scope=platform —— 变体晋升（飞轮核心·可回滚 BR-M5-12a）。
 *   GET  /ai-config                        getAiConfig          scope=platform           —— AI 配置读（密钥掩码）。
 *   PUT  /ai-config                        updateAiConfig       perm=ai.config scope=platform —— AI 配置整份覆盖（密钥保留·liveHint 强制 false）。
 *
 * 横切落地：
 *   - x-data-scope=platform：全部端点先 requirePlatform()——非平台主体一律 403 PERM_403，绝不返回护城河内容（强裁剪）。
 *     未登录由 SubjectContext.get() 抛 AUTH_401。
 *   - x-permission：写端点 @RequirePermission("ai.config") → PermissionInterceptor 缺权限 403。
 *   - 幂等：variant/promote 的 Idempotency-Key 由 IdempotencyInterceptor 在 header 层兜底（同键重放→409），控制器无需声明参数。
 *   - 敏感写（护城河内容/密钥/飞轮策略）经 AuditService 落 audit_log；密钥快照脱敏。
 *
 * Rate 口径（v1.0.3 统一分数；V911 起 DB 亦直存分数）：DB promise_rate/repay_rate/variant.uplift = 0-1 分数，与契约 Rate 一致，无需转换。
 * 金额无关：本组无金额体。
 *
 * 不可 5xx 自律：路径/体非法 → 422；非平台/缺权限 → 403；话术不存在 → 404；无胜出变体 → 409 BizError。
 */
@RestController
public class ScriptAiController {

    private static final String MASK = "****";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    public ScriptAiController(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    // ── [1] GET /script-lib ──────────────────────────────────────────────────
    // 无 x-permission：读靠 scope=platform 控可见性。非平台 → 403，绝不返回话术行。
    @GetMapping("/script-lib")
    public Page<ScriptDto> listScripts(
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requirePlatform();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (scene != null && !scene.isBlank()) {
            where.append(" AND scene = ?");
            args.add(scene);
        }
        if (source != null && !source.isBlank()) {
            where.append(" AND source = ?");
            args.add(source);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }

        String base = "FROM script_lib" + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        // 飞轮第六环 BR-M5-12a：默认按 Wilson 置信下界降序（NULL 末位），再 uses、id 兜底稳定排序。
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        String listSql = "SELECT * " + base
                + " ORDER BY wilson DESC NULLS LAST, uses DESC, id DESC LIMIT ? OFFSET ?";
        List<ScriptDto> items = jdbc.query(listSql, scriptRowMapper(), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [2] POST /script-lib ─────────────────────────────────────────────────
    // 录入专家话术 BR-M5-06a：source 固定 EXPERT，status 固定 CANDIDATE（飞轮第一环），uses=0。
    @PostMapping("/script-lib")
    @RequirePermission("ai.config")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ScriptDto createScript(@RequestBody(required = false) ScriptInputDto in) {
        CurrentSubject s = requirePlatform();
        if (in == null || in.scene() == null || in.scene().isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "scene 必填");
        }
        if (in.text() == null || in.text().isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "text 必填");
        }

        // DDL 无正文列：text 折中暂存 variant jsonb {text,state:'CANDIDATE'}（建议 V911 补 text 列）。
        String variantJson = buildVariantJson(in.text());

        Long newId = jdbc.query(
                "INSERT INTO script_lib(scene, intent, cohort, source, status, uses, variant)"
                        + " VALUES (?, ?, ?, 'EXPERT', 'CANDIDATE', 0, ?::jsonb) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                in.scene(), in.intent(), in.cohort(), variantJson);

        ScriptDto created = loadScriptOr404(String.valueOf(newId));

        // 敏感写（护城河内容）留痕：after_snap=新行。
        audit.write(s, "ai.script.create", "script_lib", created.id(),
                "录入专家话术", null, scriptSnapshot(created));
        return created;
    }

    // ── [3] POST /script-lib/{id}/variant/promote ────────────────────────────
    // 变体晋升（飞轮核心 BR-M5-12a）：单事务 + 行级锁；无胜出变体 → 409 BizError，绝不 5xx。
    @PostMapping("/script-lib/{id}/variant/promote")
    @RequirePermission("ai.config")
    @Transactional
    public Map<String, Object> promoteScriptVariant(@PathVariable String id) {
        CurrentSubject s = requirePlatform();
        long scriptId = parsePathIdOr404(id);   // 路径资源 id 非数字→404(资源不存在语义,审计 M-5),非 422

        // 行级锁取出晋升前快照（含原始列，用于 before_snap 与回滚保留）。
        Map<String, Object> before = jdbc.query(
                "SELECT id, scene, intent, cohort, source, status, uses, promise_rate, repay_rate, wilson,"
                        + " variant::text AS variant_text"
                        + " FROM script_lib WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rowToMap(rs) : null,
                scriptId);
        if (before == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "话术不存在: " + id);
        }

        // 解析 variant；无变体或非胜出态（WINNER/READY）→ 409，绝不晋升。
        String variantText = (String) before.get("variant_text");
        JsonNode variant = readTree(variantText);
        if (variant == null || variant.isNull() || !variant.isObject()) {
            throw new ApiException(BizError.STATE_409, "无可晋升的胜出变体");
        }
        JsonNode stateNode = variant.get("state");
        String state = stateNode == null || stateNode.isNull() ? null : stateNode.asText();
        if (!"WINNER".equals(state) && !"READY".equals(state)) {
            throw new ApiException(BizError.STATE_409, "变体未达晋升条件(state 非 WINNER/READY)");
        }

        // 晋升：status 升 EFFECTIVE（候选→现行），variant.state 改 PROMOTED（保留 text 供回滚）。
        // promise_rate/repay_rate/wilson 以变体实测值回填（variant 含则用，缺则保持原值）。
        ObjectNode v = (ObjectNode) variant;
        v.put("state", "PROMOTED");
        String promotedVariant;
        try {
            promotedVariant = json.writeValueAsString(v);
        } catch (Exception e) {
            // 变体序列化异常兜底：退回原文本，绝不 5xx。
            promotedVariant = variantText;
        }

        jdbc.update(
                "UPDATE script_lib SET status = 'EFFECTIVE', variant = ?::jsonb, updated_at = now()"
                        + " WHERE id = ?",
                promotedVariant, scriptId);

        ScriptDto after = loadScriptOr404(String.valueOf(scriptId));

        // 敏感写留痕（支撑可回滚）：before=晋升前行，after=晋升后行。
        audit.write(s, "ai.script.variant.promote", "script_lib", String.valueOf(scriptId),
                "变体晋升为现行", normalizeSnapshot(before), scriptSnapshot(after));
        return ok();
    }

    // ── [4] GET /ai-config ───────────────────────────────────────────────────
    // 读 settings domain='AI' 最新版本 value jsonb；密钥掩码（BR-M5-13），不回明文。
    @GetMapping("/ai-config")
    public AiConfigDto getAiConfig() {
        requirePlatform();
        AiConfigDto cfg = loadAiConfig();
        if (cfg == null) {
            // 未配置：返回空壳（非 404），前端可据此走初始化。
            return new AiConfigDto(null, null, null, null);
        }
        return maskSecrets(cfg);
    }

    // ── [5] PUT /ai-config ───────────────────────────────────────────────────
    // 整份覆盖：版本递增（乐观锁靠 uq_settings_domain_ver）；密钥掩码占位保留旧密文；liveHint 强制 false。
    @PutMapping("/ai-config")
    @RequirePermission("ai.config")
    @Transactional
    public Map<String, Object> updateAiConfig(@RequestBody(required = false) AiConfigDto in) {
        CurrentSubject s = requirePlatform();
        if (in == null) {
            throw new ApiException(BizError.VALIDATION_422, "请求体必填");
        }

        AiConfigDto old = loadAiConfig();

        // 密钥保留 BR-M5-13：请求体某敏感字段为掩码占位(****)或缺省 → 保留旧密文；仅明文新值才覆盖。
        // flywheel.liveHint 强制 false（OQ-M5-1）。
        AiConfigDto merged = mergeSecretsAndForceFlags(in, old);

        String valueJson;
        try {
            valueJson = json.writeValueAsString(merged);
        } catch (Exception e) {
            throw new ApiException(BizError.VALIDATION_422, "配置体序列化失败");
        }

        long actorId = parseId(s.accountId(), "操作人");
        try {
            jdbc.update(
                    "INSERT INTO settings(domain, version, value, updated_by)"
                            + " VALUES ('AI', (SELECT COALESCE(MAX(version),0)+1 FROM settings WHERE domain='AI'),"
                            + " ?::jsonb, ?)",
                    valueJson, actorId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发撞 uq_settings_domain_ver → 409（乐观锁冲突，绝不 5xx）。
            throw new ApiException(BizError.STATE_409, "AI 配置并发更新冲突，请重试");
        }

        // 敏感写（密钥/飞轮策略）留痕：before/after 均密钥脱敏。
        audit.write(s, "ai.config.update", "settings", "ai_config", "维护 AI 配置",
                old == null ? null : configSnapshot(maskSecrets(old)),
                configSnapshot(maskSecrets(merged)));
        return ok();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** x-data-scope=platform：非平台主体 → 403（强裁剪，护城河绝不外泄）。未登录由 SubjectContext 抛 401。 */
    private CurrentSubject requirePlatform() {
        CurrentSubject s = SubjectContext.get();
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "话术库/AI配置仅平台可见可管");
        }
        return s;
    }

    /** script_lib 行 → ScriptDto：Rate 列 /100 转分数；variant jsonb 反序列化，null 列保持 null。 */
    private RowMapper<ScriptDto> scriptRowMapper() {
        return (rs, i) -> new ScriptDto(
                String.valueOf(rs.getLong("id")),
                rs.getString("scene"),
                rs.getString("intent"),
                rs.getString("cohort"),
                rs.getString("source"),
                rs.getInt("uses"),
                rateToFraction(rs.getBigDecimal("promise_rate")),
                rateToFraction(rs.getBigDecimal("repay_rate")),
                rs.getString("status"),
                parseVariant(rs.getString("variant")));
    }

    private ScriptDto loadScriptOr404(String id) {
        List<ScriptDto> found = jdbc.query(
                "SELECT * FROM script_lib WHERE id = ?", scriptRowMapper(), Long.parseLong(id));
        if (found.isEmpty()) {
            throw new ApiException(BizError.NOT_FOUND_404, "话术不存在: " + id);
        }
        return found.get(0);
    }

    /** DB NUMERIC 现按契约口径直存分数 0-1（V911 起；如 0.4500）→ 原样透出。null 保持 null。 */
    private static Double rateToFraction(BigDecimal frac) {
        if (frac == null) return null;
        return frac.doubleValue();
    }

    /** variant jsonb 文本 → ScriptVariantDto；null/异常保持 null。uplift 亦按分数 0-1 直存直出（V911 起）。 */
    private ScriptVariantDto parseVariant(String variantText) {
        if (variantText == null || variantText.isBlank()) return null;
        try {
            JsonNode n = json.readTree(variantText);
            if (n == null || n.isNull() || !n.isObject()) return null;
            JsonNode text = n.get("text");
            JsonNode uplift = n.get("uplift");
            JsonNode state = n.get("state");
            Double upliftFrac = (uplift == null || uplift.isNull())
                    ? null
                    : rateToFraction(new BigDecimal(uplift.asText()));
            return new ScriptVariantDto(
                    text == null || text.isNull() ? null : text.asText(),
                    upliftFrac,
                    state == null || state.isNull() ? null : state.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /** 录入正文 → variant jsonb {text,state:'CANDIDATE'}（DDL 无正文列折中）。 */
    private String buildVariantJson(String text) {
        ObjectNode n = json.createObjectNode();
        n.put("text", text);
        n.put("state", "CANDIDATE");
        try {
            return json.writeValueAsString(n);
        } catch (Exception e) {
            // 退化：手工拼最小合法 jsonb，绝不 5xx（text 已非空校验过）。
            return "{\"state\":\"CANDIDATE\"}";
        }
    }

    /** settings domain='AI' 最新版本 value → AiConfigDto；无配置返回 null。 */
    private AiConfigDto loadAiConfig() {
        String valueText = jdbc.query(
                "SELECT value::text AS v FROM settings WHERE domain = 'AI' ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("v") : null);
        if (valueText == null || valueText.isBlank()) return null;
        try {
            return json.readValue(valueText, AiConfigDto.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** GET 出口掩码：llm/asr 的 apiKey 若存在则置 "****"，不回明文（BR-M5-13）。 */
    private AiConfigDto maskSecrets(AiConfigDto c) {
        AiConfigDto.Llm llm = c.llm() == null ? null : new AiConfigDto.Llm(
                c.llm().provider(), c.llm().model(), c.llm().temperature(), c.llm().maxTokens(),
                c.llm().apiKey() == null ? null : MASK);
        AiConfigDto.Asr asr = c.asr() == null ? null : new AiConfigDto.Asr(
                c.asr().provider(), c.asr().model(), c.asr().hotwords(),
                c.asr().apiKey() == null ? null : MASK);
        return new AiConfigDto(llm, asr, c.prompts(), c.flywheel());
    }

    /**
     * PUT 入参 → 落库前合并：
     *   - 密钥占位(****)或缺省 → 保留旧密文（明文新值才覆盖，BR-M5-13）；
     *   - flywheel.liveHint 强制 false（OQ-M5-1）。
     */
    private AiConfigDto mergeSecretsAndForceFlags(AiConfigDto in, AiConfigDto old) {
        AiConfigDto.Llm oldLlm = old == null ? null : old.llm();
        AiConfigDto.Llm llm = in.llm() == null ? null : new AiConfigDto.Llm(
                in.llm().provider(), in.llm().model(), in.llm().temperature(), in.llm().maxTokens(),
                resolveSecret(in.llm().apiKey(), oldLlm == null ? null : oldLlm.apiKey()));

        AiConfigDto.Asr oldAsr = old == null ? null : old.asr();
        AiConfigDto.Asr asr = in.asr() == null ? null : new AiConfigDto.Asr(
                in.asr().provider(), in.asr().model(), in.asr().hotwords(),
                resolveSecret(in.asr().apiKey(), oldAsr == null ? null : oldAsr.apiKey()));

        AiConfigDto.Flywheel fw = in.flywheel() == null
                ? new AiConfigDto.Flywheel(null, null, null, false)
                : new AiConfigDto.Flywheel(
                        in.flywheel().autoIterate(), in.flywheel().trigger(),
                        in.flywheel().adoptMode(), false);   // liveHint 恒 false

        return new AiConfigDto(llm, asr, in.prompts(), fw);
    }

    /** 密钥解析：新值为掩码占位/空 → 保留旧密文；否则用明文新值。 */
    private static String resolveSecret(String incoming, String existing) {
        if (incoming == null || incoming.isBlank() || MASK.equals(incoming)) {
            return existing;
        }
        return incoming;
    }

    // ── 留痕快照（脱敏） ───────────────────────────────────────────────────────

    private Map<String, Object> scriptSnapshot(ScriptDto d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("scene", d.scene());
        m.put("source", d.source());
        m.put("status", d.status());
        m.put("uses", d.uses());
        return m;
    }

    /** promote 的 before 取自原始列 map：仅保留关键字段、剔除变体明文，留痕用。 */
    private Map<String, Object> normalizeSnapshot(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(raw.get("id")));
        m.put("status", raw.get("status"));
        m.put("source", raw.get("source"));
        return m;
    }

    /** AiConfig 留痕快照（已掩码）：仅记非密钥结构概要。 */
    private Map<String, Object> configSnapshot(AiConfigDto c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c.llm() != null) m.put("llm.model", c.llm().model());
        if (c.asr() != null) m.put("asr.model", c.asr().model());
        if (c.flywheel() != null) {
            m.put("flywheel.autoIterate", c.flywheel().autoIterate());
            m.put("flywheel.liveHint", c.flywheel().liveHint());
        }
        return m;
    }

    // ── 低级工具 ──────────────────────────────────────────────────────────────

    private JsonNode readTree(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("scene", rs.getString("scene"));
        m.put("intent", rs.getString("intent"));
        m.put("cohort", rs.getString("cohort"));
        m.put("source", rs.getString("source"));
        m.put("status", rs.getString("status"));
        m.put("uses", rs.getInt("uses"));
        m.put("variant_text", rs.getString("variant_text"));
        return m;
    }

    /** 路径/体内 id 解析：非数字 → 422（绝不 NumberFormatException 冒泡成 5xx）。 */
    private static long parseId(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, what + " id 必填");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.VALIDATION_422, what + " id 非法: " + raw);
        }
    }

    /** 路径资源 id：非数字按"资源不存在"→404（审计 M-5，与 MasterWrite/Member 范式一致；区别于体内 id 的 422）。 */
    private static long parsePathIdOr404(String raw) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "话术不存在: " + raw);
        }
    }

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }
}
