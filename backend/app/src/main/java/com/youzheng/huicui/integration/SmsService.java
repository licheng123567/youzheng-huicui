package com.youzheng.huicui.integration;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 短信业务层（BR-M9）：封装分流 + sms_record 流水。
 *   验证码 sendVerificationCode → 普通短信（即时）。落 sms_record（org_id=NULL, template=VERIFY_CODE），仅平台可见。
 *   缴费链接 sendPayLinkSms      → 普通短信（即时），best-effort、落 sms_record（BR-M9-08 失败不退条数）。
 *   视频通知 sendVideoNotify     → 视频短信（Timing=now+11min，非即时），落 sms_record。
 * 未接入短信（ZhixunyunSmsClient.isEnabled()=false）→ 调用方走占位、不触本层网关。
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final DateTimeFormatter YMDHMS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final SecureRandom rnd = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ZhixunyunSmsClient sms;
    private final String publicBaseUrl;
    private final com.youzheng.huicui.common.BalanceService balance;
    /** v1.21.0：签名/模板/冷却一律按组织解析（四级兜底 org→settings→yml→常量），见 SmsConfigService。 */
    private final SmsConfigService cfg;

    public SmsService(JdbcTemplate jdbc, ZhixunyunSmsClient sms,
                      com.youzheng.huicui.common.BalanceService balance,
                      SmsConfigService cfg,
                      @Value("${huicui.sms.public-base-url:}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.sms = sms;
        this.balance = balance;
        this.cfg = cfg;
        this.publicBaseUrl = trimSlash(publicBaseUrl);
    }

    /** sms_record.template 的固定取值（便于 /sms-records 按类别统计与计费对账）。 */
    static final String TPL_VERIFY_CODE = "VERIFY_CODE";
    static final String TPL_PAY_LINK = "缴费链接";
    /** dry-run 落流水时的前缀，与真实发送一眼可辨、不污染计费口径。 */
    static final String DRY_RUN_PREFIX = "DRY_RUN:";

    public boolean isEnabled() { return sms.isEnabled(); }

    /** dry-run 下给 template 加前缀：走完整条链路，但流水里明确标注「这条没真发」。 */
    private String template(String base) {
        return sms.isDryRun() ? DRY_RUN_PREFIX + base : base;
    }

    /**
     * 验证码（普通短信·即时）。返回 6 位码供上层存内存校验。网关失败抛 BIZ_SMS_FAILED。
     *
     * 落 sms_record（org_id=NULL, template=VERIFY_CODE）：登录前无 org 上下文，
     * 但**验证码的发送量与费用必须可见**——否则公开端点被刷时，`/sms-records` 里一片安静。
     * V927 已把 org_id 放宽为可空；DataScope.ownOrg 让这些行只对平台可见。
     */
    public String sendVerificationCode(String phone) {
        String code = String.format("%06d", rnd.nextInt(1_000_000));
        try {
            // 验证码=平台级短信（登录前无组织上下文）：签名走平台默认(settings→yml)，模板恒用 yml 全局报备模板。
            String verifySign = cfg.resolveSignName(null);
            String tplVerify = cfg.ymlTemplateId(SmsConfigService.KIND_NOTIFY);   // NOTIFY 分支返回 verify-code 模板
            if (!tplVerify.isBlank()) {
                sms.sendSms(phone, null, tplVerify, List.of(code, "5"), verifySign);   // 模板变量 [验证码, 有效分钟]
            } else {
                sms.sendSms(phone, "您的验证码是" + code + "，5分钟内有效，请勿泄露。", null, null, verifySign);
            }
        } catch (ApiException e) {
            recordSms(null, null, null, template(TPL_VERIFY_CODE), "FAILED", e.getMessage());
            throw e;
        }
        recordSms(null, null, null, template(TPL_VERIFY_CODE), "SENT", null);
        return code;
    }

    /**
     * 缴费链接（普通短信·即时）。best-effort：失败落 FAILED 不抛、不回滚链接。
     * v1.19.0 计费（SMS 预付）：发起即扣 1 条（BR-M9-08 失败不退条数——SENT/FAILED 都计费）；
     * 额度不足 → 不发送、落 FAILED（best-effort 语义不抛）。
     */
    public void sendPayLinkSms(long caseId, long orgId, long projectId, String token) {
        String phone = primaryPhone(caseId);
        String payUrl = publicBaseUrl + "/pay/" + token;
        if (phone == null || phone.isBlank()) {
            recordSms(orgId, caseId, projectId, template(TPL_PAY_LINK), "FAILED", "案件无主号");
            return;
        }
        // v1.21.0 平台 kill-switch：该组织短信通道被停用 → 不发、不扣额度。
        if (!cfg.smsEnabled(orgId)) {
            recordSms(orgId, caseId, projectId, template(TPL_PAY_LINK), "FAILED", "该组织短信通道已停用");
            return;
        }
        if (billable(orgId) && !chargeSms(orgId, caseId, 1)) {
            recordSms(orgId, caseId, projectId, template(TPL_PAY_LINK), "FAILED", "短信额度不足");
            return;
        }
        // v1.21.0 按组织解析：签名 + 该物业的**生效**模板（ACTIVE；DRAFT/REJECTED 绝不用于发送）。
        String sign = cfg.resolveSignName(orgId);
        java.util.Optional<SmsConfigService.Tpl> orgTpl = cfg.resolveTemplate(orgId, SmsConfigService.KIND_PAY_LINK);
        String tplLabel = orgTpl.map(t -> TPL_PAY_LINK + "(#" + t.id() + ")").orElse(TPL_PAY_LINK);
        try {
            if (orgTpl.isPresent()) {
                // 变量按报备时提交的 var_order 绑定（缺值填空串，绝不错位——见 SmsVars 注释）。
                java.util.Map<String, String> ctx = new java.util.HashMap<>();
                ctx.put(SmsVars.PAY_URL, payUrl);
                sms.sendSms(phone, null, orgTpl.get().gatewayTemplateId(),
                        cfg.bindVars(orgTpl.get().varOrder(), ctx), sign);
            } else if (!cfg.ymlTemplateId(SmsConfigService.KIND_PAY_LINK).isBlank()) {
                sms.sendSms(phone, null, cfg.ymlTemplateId(SmsConfigService.KIND_PAY_LINK), List.of(payUrl), sign);
            } else {
                sms.sendSms(phone, "您有一笔物业费待缴，请点击缴费：" + payUrl, null, null, sign);
            }
            recordSms(orgId, caseId, projectId, template(tplLabel), "SENT", null);
        } catch (ApiException e) {
            log.warn("缴费链接短信发送失败 case={}: {}", caseId, e.getMessage());
            recordSms(orgId, caseId, projectId, template(tplLabel), "FAILED", e.getMessage());
        }
    }

    /** 视频短信通知（非即时，Timing=now+11min，满足平台 >10min 约束）。落 sms_record。 */
    public String sendVideoNotify(long orgId, Long caseId, Long projectId, String templateId,
                                  String phone, List<String> params) {
        // v1.19.0 计费：发起即扣 1 条；额度不足 → BIZ_QUOTA_EXHAUSTED(409)（本方法本就会抛）。
        if (billable(orgId) && !chargeSms(orgId, caseId, 1)) {
            recordSms(orgId, caseId, projectId, template("视频:" + templateId), "FAILED", "短信额度不足");
            throw new ApiException(BizError.BIZ_QUOTA_EXHAUSTED, "SMS 额度不足，请先充值");
        }
        String timing = LocalDateTime.now().plusMinutes(11).format(YMDHMS);
        try {
            String taskId = sms.sendVideoSms(templateId, timing, phone, params);
            recordSms(orgId, caseId, projectId, template("视频:" + templateId), "SENT", null);
            return taskId;
        } catch (ApiException e) {
            recordSms(orgId, caseId, projectId, template("视频:" + templateId), "FAILED", e.getMessage());
            throw e;
        }
    }

    // ── helpers ──

    /**
     * 是否计费（v1.19.0）：
     *   · 未启用（isEnabled=false）→ 不计费：调用方走占位、不触网关、无真实成本（且会让 dev/e2e 库被静默扣空）；
     *   · dry-run → 不计费：代码既定原则「DRY_RUN 不污染计费口径」；
     *   · 验证码（orgId=null）→ 不计费：登录前无 org 上下文，平台自担（sms_record 仍留痕，V927 org_id 已放宽可空）。
     */
    private boolean billable(Long orgId) {
        return orgId != null && sms.isEnabled() && !sms.isDryRun();
    }

    /** 扣 SMS 额度（预付）：足→true 并落用量/流水；不足→false（调用方决定落 FAILED 还是抛）。 */
    private boolean chargeSms(long orgId, Long caseId, int count) {
        return balance.tryCharge(orgId, com.youzheng.huicui.common.BillingUnits.SMS,
                java.math.BigDecimal.valueOf(count), caseId, "sms#case" + caseId, "短信发送扣减", null);
    }

    private String primaryPhone(long caseId) {
        List<String> ps = jdbc.query(
                "SELECT phone FROM contact WHERE case_id = ? ORDER BY is_primary DESC, id LIMIT 1",
                (rs, i) -> rs.getString("phone"), caseId);
        return ps.isEmpty() ? null : ps.get(0);
    }

    /**
     * orgId 可为 null（登录验证码：登录前无 org 上下文）。V927 已把 sms_record.org_id 放宽为可空，
     * DataScope.ownOrg 使这类行只对平台可见。
     */
    private void recordSms(Long orgId, Long caseId, Long projectId, String template, String status, String failure) {
        try {
            jdbc.update(
                    "INSERT INTO sms_record(org_id, case_id, project_id, template, status, failure_reason)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    orgId, caseId, projectId, template, status,
                    failure == null ? null : (failure.length() > 500 ? failure.substring(0, 500) : failure));
        } catch (RuntimeException e) {
            log.warn("写 sms_record 失败（不阻断）: {}", e.toString());
        }
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
