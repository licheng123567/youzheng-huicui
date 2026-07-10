# 短信通道对接清单（智讯云 / 028lk）

代码侧**已就绪并可验证**：`ZhixunyunSmsClient` + `SmsBodyBuilder` + `SmsService` 三件套齐备，
`dry-run` 模式让整条链路（冷却 → `sms_record` → pay-link 状态机 → `/sms-records`）在**没有凭据**时也能跑通。
拿到凭据后只需填 `.env`、关 dry-run、重启。

---

## 一、需要向智讯云索取的四项

| 配置项 | 环境变量 | 说明 |
|---|---|---|
| SecretName | `HUICUI_SMS_SECRET_NAME` | 明文鉴权账号名 |
| SecretKey | `HUICUI_SMS_SECRET_KEY` | 明文鉴权密钥 |
| **普通短信接口地址** | `HUICUI_SMS_BASE` | **当前为空**。缺它 `sendSms` 直接 502。客户端会拼 `{HUICUI_SMS_BASE}/Sms/Api/Send` |
| 已报备的模板 ID | `HUICUI_SMS_TPL_VERIFY` / `HUICUI_SMS_TPL_PAYLINK` | 留空则走「明文 Content」方式（需网关允许） |

视频短信地址 `HUICUI_SMS_VIDEO_BASE` 默认 `http://api.028lk.com`，本期**无任何调用点**，不必配。

## 二、签名与模板

签名 `【有证慧催】`（`HUICUI_SMS_SIGN`）**已报备通过**，配置好即可发送。

模板变量顺序**必须与 `SmsService` 一致**，否则填充错位：

| 用途 | 变量顺序（代码里传的） | 建议文案 |
|---|---|---|
| 验证码 | `[code, "5"]` | `您的验证码是{1}，{2}分钟内有效，请勿泄露。` |
| 缴费链接 | `[payUrl]` | `您有一笔物业费待缴，请点击缴费：{1}` |

`payUrl` = `HUICUI_PUBLIC_BASE` + `/pay/{token}`。

> 若模板尚未报备，可留空模板 ID 走明文 `Content`；但多数网关对明文长短信有额外审核，实际能否发通须与对接方确认。
> 一旦模板报备完成，填入模板 ID 即自动切到模板方式（`SmsService` 按「模板 ID 非空」分支）。

---

## 三、dry-run：没有凭据也能把整条链路跑起来

```bash
HUICUI_SMS_ENABLED=true \
HUICUI_SMS_DRY_RUN=true \
HUICUI_SMS_SECRET_NAME=stub \
HUICUI_SMS_SECRET_KEY=stub \
mvn spring-boot:run          # dev profile
```

> **dev 与 prod 的 dry-run 门槛不同**：dev 下 `HUICUI_SMS_BASE` 可以留空（dry-run 在拼 URL 之前就返回了）；
> 但 **prod profile 下 `ProdEnvironmentGuard` 仍会硬校验** `SECRET_NAME`/`SECRET_KEY`/`SMS_BASE` 三者非空，
> 缺一即拒绝启动。这是有意的：预发演练要跑在与生产**同样完整**的配置上，否则演练过了、真上线才发现 `SMS_BASE` 忘了填。

行为：

- **不触网关、不产生费用**；`sendSms`/`sendVideoSms` 直接返回 `DRYRUN-<uuid>`。
- 验证码正常随机生成，**正文（含验证码）写进日志**（`[SMS DRY-RUN]` WARN 行）——
  这是有意的：预发演练时人得能真的登录一次。
- 冷却、`sms_record`、pay-link 状态机、`/sms-records` **全部按真实路径走**。
- 落流水的 `template` 带 `DRY_RUN:` 前缀（如 `DRY_RUN:VERIFY_CODE`），不污染计费口径。
- prod profile 下开 dry-run，`ProdGuard.announce()` 会打一段刺眼 WARN（不硬失败，允许预发演练）。
  它只在 `enabled=true && dryRun=true` 时出现 —— 「以为在发短信、其实一条没发」是最贵的静默失败。

**已实测**（dev + dry-run，净室验证）：
`/auth/sms-code` → 200；日志出现 `[SMS DRY-RUN] ... phone=137****2222 ... content=您的验证码是587376…`；
`sms_record` 落 `(org_id=NULL, DRY_RUN:VERIFY_CODE, SENT)`；用日志里的码走通短信登录（一号多账号返回 loginTicket + 两账号）；
60s 内重发 → `429 retryAfterSeconds:59`；平台 `/sms-records` 看得到该行，`cuihu_pl` / `jx_vl` 看不到。

---

## 四、切到真实通道

1. 填 `deploy/.env`（**该文件不入库**，`.gitignore` 已拦）：
   ```
   HUICUI_SMS_ENABLED=true
   HUICUI_SMS_DRY_RUN=false
   HUICUI_SMS_SECRET_NAME=<对接方给>
   HUICUI_SMS_SECRET_KEY=<对接方给>
   HUICUI_SMS_BASE=<普通短信接口地址>
   HUICUI_SMS_SIGN=【有证慧催】
   HUICUI_SMS_TPL_VERIFY=<模板id 或留空>
   HUICUI_SMS_TPL_PAYLINK=<模板id 或留空>
   HUICUI_PUBLIC_BASE=https://<真实域名>
   ```
2. 重启。`ProdEnvironmentGuard` 会替你把关：短信启用但 `PUBLIC_BASE` 仍是 localhost、
   或 `SECRET_NAME`/`SECRET_KEY`/`SMS_BASE` 为空 → **拒绝启动**（它是 EnvironmentPostProcessor，
   抢在任何 bean 创建之前跑，报错是可操作的中文提示）。
3. 用真号收一条验证码、一条缴费链接短信。

> 一旦 `HUICUI_SMS_ENABLED=true`，dev 固定码 `000000` 即失效（`AuthController` 优先走真实通道）。
> 非 dev 环境**从来就没有**固定码：短信未启用时 `/auth/sms-code` 一律 `502 BIZ_SMS_FAILED`。

---

## 五、费用与冷却口径

- **失败不退条数**（BR-M9-08）：`FAILED` 行照常留痕，`failure_reason` 只存对外文案。
- 同案 SMS 缴费链接冷却默认 **6h**（`settings` 表 `domain='SMS'` 的 `cooldownSeconds` 可改）；
  命中冷却 → `409 BIZ_SMS_COOLDOWN`。
- 缴费链接有效期默认 **7d**（`settings.SMS.payLinkTtlSeconds`）。
- 登录验证码冷却 **60s/手机号**，命中 → `429` 带 `retryAfterSeconds`。
- 验证码短信现在**会落 `sms_record`**（`org_id=NULL`, `template=VERIFY_CODE`），
  仅平台可见（`DataScope.ownOrg` 对平台不加过滤）。这是发现短信轰炸的前提 —— 上线后请把它纳入监控。

---

## 六、明确**不做**（别产生幻觉）

- **计费扣条数**：`PayReduceRepayM4Controller:100` 的 `TODO(M9)` 仍在。短信目前**既不校验余量也不扣费**。
- **`DELIVERED` 回填**：没有网关送达回调，`sms_record.status` 只会出现 `SENT` / `FAILED`。
- **视频短信通道**：`sendVideoNotify` 已实现但**零调用点**，本期不启用。
- **`/sms-records/export`**：仍返回 `{url: null}`（文件通道 TBD）。
- **频控换 Redis**：`loginTicket` / 验证码 / 发送冷却三者仍在**进程内存**。
  本次只加了过期清扫（`AuthController.sweepExpired`），**没有**解决多实例问题。
  横向扩容或滚动发布前必须先换 Redis，否则：A 实例发的票据到 B 实例换不到 token，冷却各算各的、限流形同虚设。
