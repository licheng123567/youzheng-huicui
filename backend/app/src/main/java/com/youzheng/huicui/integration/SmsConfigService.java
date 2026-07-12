package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 组织短信配置解析（v1.21.0·V934）。全仓「签名/模板/冷却/TTL」的唯一解析入口。
 *
 * 【四级兜底解析链】org_sms_config（组织权威·平台配）→ settings.sms（平台默认）→ application.yml → 硬编码常量。
 *   任何一级缺失都必须仍能发出短信——新建的物业组织不会自动建配置行，绝不能静默失败。
 *
 * 【不缓存（有意为之）】发短信不是高频路径：一次 PK 命中的单行 SELECT（~0.1ms）相对一次网关 HTTP（数百 ms）
 *   是噪声。缓存会引入「平台改签名 → 物业几分钟后才生效」的一致性坑，而这恰恰是本功能的核心验收点。
 *   将来若做批量群发，再加 @Cacheable + PUT 端点 @CacheEvict。
 *
 * 【验证码永不按组织解析】sendVerificationCode 的 orgId=null（登录前无组织上下文）——签名走
 *   settings.sms→yml，模板恒用 yml 全局报备模板。故 org_sms_template.kind 不含 VERIFY_CODE。
 *
 * 【修既有 bug】settings.sms 此前是「只写不读的黑洞」（SA 在参数配置页填的签名/模板后端从不读）；
 *   且 UI/契约写 cooldownMinutes(分) 而后端读 cooldownSeconds(秒) → 改了永不生效。
 *   本类把 settings.sms 真正读起来（作为平台默认），且 cooldown **两个键都吃**（秒优先，缺则分×60）。
 */
@Service
public class SmsConfigService {

    /** 同案短信冷却兜底：6 小时（CFG-SMS-COOLDOWN）。 */
    public static final long DEFAULT_COOLDOWN_SECONDS = 6L * 3600;
    /** 缴费链接有效期兜底：7 天。 */
    public static final long DEFAULT_PAYLINK_TTL_SECONDS = 7L * 24 * 3600;
    private static final String DEFAULT_SIGN = "【有证慧催】";

    public static final String KIND_PAY_LINK = "PAY_LINK";
    public static final String KIND_NOTIFY = "NOTIFY";
    public static final String KIND_VIDEO_NOTIFY = "VIDEO_NOTIFY";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String ymlSignName;
    private final String ymlTplVerify;
    private final String ymlTplPayLink;

    public SmsConfigService(JdbcTemplate jdbc, ObjectMapper json,
                            @Value("${huicui.sms.sign-name:}") String ymlSignName,
                            @Value("${huicui.sms.templates.verify-code:}") String ymlTplVerify,
                            @Value("${huicui.sms.templates.pay-link:}") String ymlTplPayLink) {
        this.jdbc = jdbc;
        this.json = json;
        this.ymlSignName = ymlSignName;
        this.ymlTplVerify = ymlTplVerify;
        this.ymlTplPayLink = ymlTplPayLink;
    }

    /** 生效模板（含报备号与变量顺序）。 */
    public record Tpl(long id, String name, String gatewayTemplateId, String content, List<String> varOrder) {}

    // ── 签名 ───────────────────────────────────────────────────────────────

    /** 签名解析：org_sms_config → settings.sms.signature → yml sign-name → 【有证慧催】。orgId=null（验证码）跳过组织级。 */
    public String resolveSignName(Long orgId) {
        if (orgId != null) {
            String s = jdbc.query("SELECT sign_name FROM org_sms_config WHERE org_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, orgId);
            if (s != null && !s.isBlank()) return s;
        }
        String def = settingsText("signature");
        if (def != null && !def.isBlank()) return def;
        return (ymlSignName != null && !ymlSignName.isBlank()) ? ymlSignName : DEFAULT_SIGN;
    }

    // ── 模板 ───────────────────────────────────────────────────────────────

    /** 该组织该用途的**生效**模板（status=ACTIVE）。无 → empty（调用方回落 yml 全局模板 / 明文）。 */
    public Optional<Tpl> resolveTemplate(Long orgId, String kind) {
        if (orgId == null) return Optional.empty();          // 验证码等平台级短信不按组织走
        List<Tpl> rows = jdbc.query(
                "SELECT id, name, gateway_template_id, content, var_order::text AS vo"
                        + " FROM org_sms_template WHERE org_id = ? AND kind = ? AND status = 'ACTIVE' LIMIT 1",
                (rs, i) -> new Tpl(rs.getLong("id"), rs.getString("name"),
                        rs.getString("gateway_template_id"), rs.getString("content"),
                        parseVarOrder(rs.getString("vo"))),
                orgId, kind);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** yml 的全局报备模板 ID（验证码恒用；缴费链接在组织无 ACTIVE 模板时兜底）。 */
    public String ymlTemplateId(String kind) {
        return KIND_PAY_LINK.equals(kind) ? ymlTplPayLink : ymlTplVerify;
    }

    // ── 冷却 / TTL / 阈值 / 开关 ──────────────────────────────────────────

    /** 同案短信冷却秒：org_sms_config → settings(cooldownSeconds 优先，缺则 cooldownMinutes×60) → 6h。 */
    public long cooldownSeconds(Long orgId) {
        Long org = orgLong(orgId, "cooldown_seconds");
        if (org != null) return org;
        Long sec = settingsLong("cooldownSeconds");
        if (sec != null) return sec;
        Long min = settingsLong("cooldownMinutes");           // 修 bug：UI/契约写的是分钟
        if (min != null) return min * 60;
        return DEFAULT_COOLDOWN_SECONDS;
    }

    /** 缴费链接有效期秒：org_sms_config → settings.payLinkTtlSeconds → 7d。 */
    public long payLinkTtlSeconds(Long orgId) {
        Long org = orgLong(orgId, "pay_link_ttl_seconds");
        if (org != null) return org;
        Long s = settingsLong("payLinkTtlSeconds");
        return s != null ? s : DEFAULT_PAYLINK_TTL_SECONDS;
    }

    /** 短信余额预警阈值（条）；无配置→settings→null（不预警）。 */
    public Integer warnThreshold(Long orgId) {
        Long org = orgLong(orgId, "warn_threshold");
        if (org != null) return org.intValue();
        Long s = settingsLong("warnThreshold");
        return s == null ? null : s.intValue();
    }

    /** 组织短信通道开关（平台 kill-switch）。缺配置行 → true（默认可发，见类注释的兜底原则）。 */
    public boolean smsEnabled(Long orgId) {
        if (orgId == null) return true;                       // 验证码等平台级短信不受组织开关约束
        Boolean b = jdbc.query("SELECT enabled FROM org_sms_config WHERE org_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, orgId);
        return b == null || b;
    }

    // ── 变量绑定（防报备错位）────────────────────────────────────────────

    /**
     * 按模板的 var_order 从上下文取值组装网关变量数组。**缺值填空串而非跳过**——
     * 跳过会让后续变量整体前移（把「张三，欠费 3600 元」发成「3600，欠费 张三 元」），是最危险的失败模式。
     */
    public List<String> bindVars(List<String> varOrder, java.util.Map<String, String> ctx) {
        List<String> out = new ArrayList<>();
        if (varOrder == null) return out;
        for (String k : varOrder) {
            String v = ctx.get(k);
            out.add(v == null ? "" : v);
        }
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Long orgLong(Long orgId, String col) {
        if (orgId == null) return null;
        return jdbc.query("SELECT " + col + " FROM org_sms_config WHERE org_id = ?",
                rs -> rs.next() ? (Long) (Object) rs.getLong(col) : null, orgId);
    }

    /** settings.sms 的文本键（平台默认；v1.21.0 起真正被读取——此前是只写不读的黑洞）。 */
    private String settingsText(String key) {
        return jdbc.query(
                "SELECT sms ->> ? AS v FROM settings WHERE domain = 'SMS' ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("v") : null, key);
    }

    private Long settingsLong(String key) {
        String v = settingsText(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;                                       // 脏配置不致 5xx，回落下一级
        }
    }

    private List<String> parseVarOrder(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
