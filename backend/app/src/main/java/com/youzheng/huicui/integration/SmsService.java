package com.youzheng.huicui.integration;

import com.youzheng.huicui.error.ApiException;
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
    private final String signName;
    private final String publicBaseUrl;
    private final String tplVerify;
    private final String tplPayLink;

    public SmsService(JdbcTemplate jdbc, ZhixunyunSmsClient sms,
                      @Value("${huicui.sms.sign-name:}") String signName,
                      @Value("${huicui.sms.public-base-url:}") String publicBaseUrl,
                      @Value("${huicui.sms.templates.verify-code:}") String tplVerify,
                      @Value("${huicui.sms.templates.pay-link:}") String tplPayLink) {
        this.jdbc = jdbc;
        this.sms = sms;
        this.signName = signName;
        this.publicBaseUrl = trimSlash(publicBaseUrl);
        this.tplVerify = tplVerify;
        this.tplPayLink = tplPayLink;
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
            if (!tplVerify.isBlank()) {
                sms.sendSms(phone, null, tplVerify, List.of(code, "5"), signName);   // 模板变量 [验证码, 有效分钟]
            } else {
                sms.sendSms(phone, "您的验证码是" + code + "，5分钟内有效，请勿泄露。", null, null, signName);
            }
        } catch (ApiException e) {
            recordSms(null, null, null, template(TPL_VERIFY_CODE), "FAILED", e.getMessage());
            throw e;
        }
        recordSms(null, null, null, template(TPL_VERIFY_CODE), "SENT", null);
        return code;
    }

    /** 缴费链接（普通短信·即时）。best-effort：失败落 FAILED 不抛、不回滚链接。 */
    public void sendPayLinkSms(long caseId, long orgId, long projectId, String token) {
        String phone = primaryPhone(caseId);
        String payUrl = publicBaseUrl + "/pay/" + token;
        if (phone == null || phone.isBlank()) {
            recordSms(orgId, caseId, projectId, template(TPL_PAY_LINK), "FAILED", "案件无主号");
            return;
        }
        try {
            if (!tplPayLink.isBlank()) {
                sms.sendSms(phone, null, tplPayLink, List.of(payUrl), signName);
            } else {
                sms.sendSms(phone, "您有一笔物业费待缴，请点击缴费：" + payUrl, null, null, signName);
            }
            recordSms(orgId, caseId, projectId, template(TPL_PAY_LINK), "SENT", null);
        } catch (ApiException e) {
            log.warn("缴费链接短信发送失败 case={}: {}", caseId, e.getMessage());
            recordSms(orgId, caseId, projectId, template(TPL_PAY_LINK), "FAILED", e.getMessage());
        }
    }

    /** 视频短信通知（非即时，Timing=now+11min，满足平台 >10min 约束）。落 sms_record。 */
    public String sendVideoNotify(long orgId, Long caseId, Long projectId, String templateId,
                                  String phone, List<String> params) {
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
