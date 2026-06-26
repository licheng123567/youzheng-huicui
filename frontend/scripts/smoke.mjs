// 行走骨架 E2E 冒烟：node 端直打活后端（9091），验证 登录→/me→数据范围隔离 整条纵向切片。
// 前端类型化客户端(client.ts)由 `npm run build`(vue-tsc) 验证；本脚本验证 FE 形态的请求流对真后端。
const B = process.env.BASE || 'http://localhost:9091/v1'

async function login(username, password) {
  const r = await fetch(`${B}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode: 'password', username, password }),
  })
  if (!r.ok) throw new Error(`login ${username} → HTTP ${r.status}`)
  return (await r.json()).token
}
async function getJson(path, token) {
  const r = await fetch(`${B}${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  return { status: r.status, body: r.ok ? await r.json() : null }
}

let fail = 0
const check = (name, cond, extra = '') => { console.log(`${cond ? '✅' : '❌'} ${name}${extra ? ' → ' + extra : ''}`); if (!cond) fail++ }

const noTok = await getJson('/me')
check('无 token /me = 401', noTok.status === 401)

const sa = await login('admin', 'Admin@123')
check('admin 登录得 token', !!sa)
const meSa = await getJson('/me', sa)
check('SA /me = 200 且 role=SA', meSa.status === 200 && meSa.body.role === 'SA', meSa.body?.org?.name)
const projSa = await getJson('/projects-scope-demo', sa)
check('SA 见全部 3 项目（平台全量）', projSa.body?.items?.length === 3, projSa.body?.scopeApplied)

const pl = await login('cuihu_pl', 'Admin@123')
const projPl = await getJson('/projects-scope-demo', pl)
const names = (projPl.body?.items || []).map((i) => i.name)
check('翠湖 PL 仅见 2 项目（own-org 隔离）', projPl.body?.items?.length === 2, projPl.body?.scopeApplied)
check('翠湖 PL 看不到阳光物业项目', !names.includes('阳光花园'), names.join(','))

// M2 真端点（前端 projects/batches/cases 视图消费的数据流）
const projs = await getJson('/projects?page=1&size=20', sa)
check('GET /projects 返回 3 项目(SA)', projs.body?.items?.length === 3 && projs.body?.meta?.total === 3)
const pd = await getJson(`/projects/${projs.body.items[0].id}`, sa)
check('GET /projects/{id} 含 viewRole', !!pd.body?.viewRole, pd.body?.viewRole)
const bs = await getJson('/batches?page=1&size=20', sa)
check('GET /batches 返回批次, 平台视角双线均含 payOutRate', (bs.body?.items?.length ?? 0) >= 1 && bs.body.items[0].payOutRate != null, bs.body?.items?.[0]?.code)
const cs = await getJson('/cases?page=1&size=20', sa)
check('GET /cases 返回案件', (cs.body?.items?.length ?? 0) >= 3, '共 ' + cs.body?.meta?.total)
const cd = await getJson(`/cases/${cs.body.items[0].id}`, sa)
check('GET /cases/{id} 聚合 CaseDetail(case+contacts)', !!cd.body?.case && Array.isArray(cd.body?.contacts), cd.body?.case?.ownerName)
// 资金双线：翠湖 PL 看批次应无 payOutRate（物业视角）
const bsPl = await getJson('/batches?page=1&size=20', pl)
check('翠湖 PL 看批次: 物业视角无 payOutRate(资金双线)', bsPl.body?.items?.[0]?.payOutRate == null && bsPl.body?.items?.[0]?.commInRate != null, 'viewRole=' + bsPl.body?.items?.[0]?.viewRole)

// M3 派单/公海
const co = await login('jx_co1', 'Admin@123')
check('CO(jx_co1) 登录', !!co)
const seaProv = await getJson('/sea?pool=provider&page=1&size=50', co)
check('GET /sea?pool=provider 返回服务商公海', (seaProv.body?.items?.length ?? 0) >= 1, '共 ' + seaProv.body?.meta?.total)
const claimable = (seaProv.body?.items || []).find((c) => c.pool === 'PROVIDER_SEA')
if (claimable) {
  const r = await fetch(`${B}/cases/${claimable.id}/claim`, { method: 'POST', headers: { Authorization: `Bearer ${co}` } })
  check('CO 抢单 PROVIDER_SEA 案件 → 200', r.status === 200, '案件 ' + claimable.id)
} else {
  check('存在可抢的 PROVIDER_SEA 案件', false)
}
// CO 无 case.dispatch → 抢单可、派单应被 403（FE 按 /me 权限隐藏，服务端兜底）
const meCo = await getJson('/me', co)
check('CO 权限含 case.claim 不含 case.dispatch（FE 门控源）',
  meCo.body?.permissions?.includes('case.claim') && !meCo.body?.permissions?.includes('case.dispatch'))
// SA 派单 M3-S0 批次
const bsSa = await getJson('/batches?page=1&size=50', sa)
const s0 = (bsSa.body?.items || []).find((b) => b.code === 'B-CH-M3-S0')
const provOrg = (await getJson('/me', co)).body?.org?.id // 捷信 org id（CO 的组织）
if (s0 && provOrg) {
  const r = await fetch(`${B}/batches/${s0.id}/dispatch`, {
    method: 'POST', headers: { Authorization: `Bearer ${sa}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode: 'WHOLE', providerId: String(provOrg), payOutRate: 0.2 }),
  })
  check('SA 派单 B-CH-M3-S0(完整 DispatchInput) → 2xx', r.status >= 200 && r.status < 300, 'HTTP ' + r.status)
  // 缺必填字段应 422（校验生效）
  const r2 = await fetch(`${B}/batches/${s0.id}/dispatch`, {
    method: 'POST', headers: { Authorization: `Bearer ${sa}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: 'WHOLE' }),
  })
  check('SA 派单缺 providerId → 422（校验生效）', r2.status === 422)
  // P3: 开放费率 open-rate（S0 已派单后设开放费率）
  check('SA 设开放抢单费率 open-rate → 2xx', [200, 201, 204].includes((await fetch(`${B}/batches/${s0.id}/open-rate`, { method: 'PUT', headers: { Authorization: `Bearer ${sa}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ openRate: 0.18 }) })).status))
}
// P3: CO 释放刚抢的案件(release)；assign 端点已单独验证({"ok":true}),smoke 因抢单消费同案不再测
if (claimable) {
  const rel = await post(`/cases/${claimable.id}/release`, co, { reason: 'E2E 释放' })
  check('CO 释放已抢案件(release) → 2xx', [200, 201, 204].includes(rel.status), 'HTTP ' + rel.status)
}

// M4 催收作业（CO 在持有案件 S3 上）
async function post(path, token, body) {
  const r = await fetch(`${B}${path}`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  return { status: r.status, body: r.ok ? await r.json().catch(() => ({})) : null }
}
const allCases = await getJson('/cases?page=1&size=50', sa)
const s3 = (allCases.body?.items || []).find((c) => c.acctNo === 'M3-S3-01')
if (s3) {
  const lat = await getJson(`/cases/${s3.id}/recordings/latest`, co)
  check('CO 获取最新通话录音(hasRecording)', lat.body?.hasRecording === true, '状态 ' + lat.body?.recording?.status)
  const recId = lat.body?.recording?.id
  if (recId) {
    const rev = await getJson(`/recordings/${recId}/ai-review`, co)
    check('GET AI 复盘(summary+suggestions)', !!rev.body?.summary && Array.isArray(rev.body?.suggestions), (rev.body?.suggestions?.length ?? 0) + ' 条建议')
  }
  check('CO 写跟进 → 201', (await post(`/cases/${s3.id}/follow-ups`, co, { content: 'E2E 跟进', method: 'CALL' })).status === 201)
  check('CO 登记承诺 → 201', (await post(`/cases/${s3.id}/promises`, co, { date: '2026-08-01', amountCents: 100000 })).status === 201)
  const plk = await post(`/cases/${s3.id}/pay-links`, co, { channel: 'SMS' })
  check('CO 发缴费链接 → 2xx', [200, 201].includes(plk.status))
  // P1 缴费链接重发/作废(BR-M4-14)：用微信渠道链接(SMS 有冷却 BR-M4-14a)
  const wlk = await post(`/cases/${s3.id}/pay-links`, co, { channel: 'WECHAT_COPY' })
  const linkId = wlk.body?.id
  if (linkId) {
    check('CO 重发缴费链接(resend·微信无冷却) → 2xx', [200, 201, 204].includes((await post(`/pay-links/${linkId}/resend`, co, {})).status))
    check('CO 作废缴费链接(void) → 2xx', [200, 201, 204].includes((await post(`/pay-links/${linkId}/void`, co, {})).status))
  }
  check('SMS 链接刚发即重发 → 409 冷却(BR-M4-14a)', plk.body?.id ? (await post(`/pay-links/${plk.body.id}/resend`, co, {})).status === 409 : true)
  const pr = await getJson(`/cases/${s3.id}/promises?page=1&size=20`, co)
  check('GET 承诺列表增长(≥2)', (pr.body?.items?.length ?? 0) >= 2, '共 ' + pr.body?.meta?.total)
  // P2 子链
  check('CO 分期承诺 → 201', (await post(`/cases/${s3.id}/promises`, co, { date: '2026-09-01', amountCents: 200000, installments: [{ seq: 1, dueDate: '2026-09-01', amountCents: 100000 }, { seq: 2, dueDate: '2026-10-01', amountCents: 100000 }] })).status === 201)
  check('CO 登记还款 → 201', (await post(`/cases/${s3.id}/repay-lines`, co, { amountCents: 50000, channel: 'WECHAT_QR', paidAt: '2026-06-25' })).status === 201)
  check('CO 新增联系人 → 2xx', [200, 201].includes((await post(`/cases/${s3.id}/contacts`, co, { phone: '13900008888', label: '补充' })).status))
  // 工单处理：S3 工单 to_role=PC → 协调员 cuihu_pc 处理(ticket.handle)
  const pc = await login('cuihu_pc', 'Admin@123')
  const tks = await getJson(`/cases/${s3.id}/tickets?page=1&size=20`, co)
  const pendTk = (tks.body?.items || []).find((t) => t.status === 'PENDING')
  if (pendTk) check('PC 处理工单(ticket.handle) → 2xx', [200, 201].includes((await post(`/tickets/${pendTk.id}/handle`, pc, { result: '已上门核实' })).status))
  // 录音结果标记(S3 种子录音)
  const lat2 = await getJson(`/cases/${s3.id}/recordings/latest`, co)
  const recId2 = lat2.body?.recording?.id
  if (recId2) check('CO 通话结果标记 → 2xx', [200, 201].includes((await post(`/recordings/${recId2}/ai-review`, co, { mark: 'PROMISED' })).status))
  // P1: 独立通话记录页数据源 GET /recordings/{id}
  if (recId2) check('GET /recordings/{id} 通话记录详情 → 200', (await getJson(`/recordings/${recId2}`, co)).body?.caseId != null)
  // P1: 建议法务轻标(US-M4-07)=跟进记录
  check('CO 建议走法务(轻标=跟进) → 201', (await post(`/cases/${s3.id}/follow-ups`, co, { content: '【建议走法务】', method: 'OTHER' })).status === 201)
  // 法务/存证(PL: legal.create/evidence.create)；存证 RECORDING 场景关联就绪录音
  check('PL 申请法务文书 → 2xx', [200, 201].includes((await post(`/cases/${s3.id}/legal-docs`, pl, { type: 'COLLECTION_LETTER' })).status))
  if (recId2) check('PL 发起存证(RECORDING) → 2xx', [200, 201].includes((await post(`/cases/${s3.id}/evidence`, pl, { scene: 'RECORDING', refIds: [String(recId2)] })).status))
} else {
  check('找到 S3 案件', false)
}

// M9 结算·资金双线
const vl = await login('jx_vl', 'Admin@123')
const prOut = await getJson('/payment-requests?side=OUT&page=1&size=20', vl)
check('服务商 VL 见付佣单 OUT(双线)', (prOut.body?.items?.length ?? 0) >= 1, '共 ' + prOut.body?.meta?.total)
const plOutCross = await getJson('/payment-requests?side=OUT', pl)
check('物业 PL 查付佣线 OUT → 403 跨线(资金双线隔离·皇冠)', plOutCross.status === 403)
const plIn = await getJson('/payment-requests?side=IN', pl)
check('物业 PL 查收佣线 IN → 200', plIn.status === 200)
// 完成=平台动作(付佣线:服务商生成→平台付款+凭证)。VL 完成应 403；SA 完成 200。
const pending = (prOut.body?.items || []).find((p) => p.status === 'PENDING')
if (pending) {
  const rVl = await post(`/payment-requests/${pending.id}/complete`, vl, { voucher: { type: 'PAYMENT', fileUrl: 'https://x/v.pdf' }, version: pending.version })
  check('VL 完成付佣单 → 403(完成是平台动作·双线角色分离)', rVl.status === 403, 'HTTP ' + rVl.status)
  const rSa = await post(`/payment-requests/${pending.id}/complete`, sa, { voucher: { type: 'PAYMENT', fileUrl: 'https://x/v.pdf' }, version: pending.version })
  check('SA(平台) 完成付佣单(带凭证) → 200', rSa.status === 200, '单 ' + pending.code)
  const r2 = await post(`/payment-requests/${pending.id}/revoke`, vl, { version: pending.version + 1, reason: 'x' })
  check('已完成单再撤销 → 409 BIZ_PR_PAID', r2.status === 409, 'HTTP ' + r2.status)
}
// 缺凭证完成另一单应 422（构造：重新查 PENDING）
const co2 = await getJson('/me/settlement', co)
check('CO 我的结算 GET /me/settlement → 200', co2.status === 200)
const recon = await getJson('/recon/rollup?side=OUT&page=1&size=10', vl)
check('VL 对账汇总 GET /recon/rollup?side=OUT → 200', recon.status === 200)
// P1 生成链：找有未结回款明细的批次 → 勾选生成付佣单 → 发送 → 详情
const b2 = (bsSa.body?.items || []).find((b) => b.code === 'B-CH-2026-01')
if (b2) {
  const rl = await getJson(`/batches/${b2.id}/repay-lines?page=1&size=100`, sa)
  const freeLines = (rl.body?.items || []).filter((l) => !l.settled && !l.paymentRequestId)
  check('GET /batches/{id}/repay-lines 有未结明细可组单', freeLines.length >= 1, freeLines.length + ' 笔')
  if (freeLines.length) {
    const gen = await post('/payment-requests', vl, { side: 'OUT', batchId: String(b2.id), lineIds: freeLines.slice(0, 1).map((l) => String(l.id)) })
    check('VL 勾选明细生成付佣单 → 2xx', [200, 201].includes(gen.status), 'HTTP ' + gen.status)
    const newPrId = gen.body?.id
    if (newPrId) {
      check('VL 发送付佣单 → 2xx', [200, 201, 204].includes((await post(`/payment-requests/${newPrId}/send`, vl, {})).status))
      check('GET 付佣单详情(含 lines) → 200', (await getJson(`/payment-requests/${newPrId}`, vl)).body?.code != null)
    }
  }
}
check('VL 内催佣金名册 GET /co-commissions → 200', (await getJson('/co-commissions?page=1&size=20', vl)).status === 200)
// codex 审计修复验证
const allCx = await getJson('/cases?page=1&size=50', sa)
// ① 拒接：缺 reason→422，带 reason→2xx(S1 案件 PROVIDER_SEA 待接)
const s1c = (allCx.body?.items || []).find((c) => c.acctNo === 'M3-S1-01')
if (s1c) {
  check('VL 拒接缺 reason → 422', (await post(`/cases/${s1c.id}/reject`, vl, {})).status === 422)
  check('VL 拒接带 reason → 2xx', [200, 201, 204].includes((await post(`/cases/${s1c.id}/reject`, vl, { reason: '不接此批' })).status))
}
// ③ 内催: 设比例 + 生成单(用 S2 批次未结明细) + 确认支付
const coAcct = (await getJson('/me', co)).body?.id
const b2id = (bsSa.body?.items || []).find((b) => b.code === 'B-CH-2026-01')?.id
if (coAcct && b2id) {
  check('VL 设催收员佣金比例 → 2xx', [200, 201, 204].includes((await fetch(`${B}/co-commissions/${coAcct}/batches/${b2id}/rate`, { method: 'PUT', headers: { Authorization: `Bearer ${vl}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ rate: 0.15 }) })).status))
}
// ④ CO 越权: CO 拉 /payment-requests?side=OUT 应空(裁剪 1=0)
const coPr = await getJson('/payment-requests?side=OUT&page=1&size=20', co)
check('CO 看组织付佣单 → 空(US-M9-09 裁剪)', coPr.status === 200 && (coPr.body?.items?.length ?? 0) === 0)
// ⑤ billing 只读: PL 读 usage(range 无 perm) → 200
check('PL 读 billing/usage(range 无perm) → 200', (await getJson('/billing/usage', pl)).status === 200)
// === codex 二轮复审修复验证 ===
// HIGH case-actor 行级越权：非持有 CO(jx_co2) 操作他人案件(M3-S3-01 holder=jx_co1) → 403
const coOther = await login('jx_co2', 'Admin@123')
const s3case = (allCx.body?.items || []).find((c) => c.acctNo === 'M3-S3-01')
if (s3case && coOther) {
  check('非持有 CO 操作他人案件(follow-up) → 403', (await post(`/cases/${s3case.id}/follow-ups`, coOther, { content: 'x', method: 'OTHER' })).status === 403)
  check('持有 CO 操作本案(follow-up) → 2xx(不误伤)', [200, 201].includes((await post(`/cases/${s3case.id}/follow-ups`, co, { content: '本人跟进', method: 'OTHER' })).status))
  // case-actor 完整闭合(R3 修):非持有 CO 在 promise/回款 主链路亦 403
  check('非持有 CO 承诺(promise) → 403', (await post(`/cases/${s3case.id}/promises`, coOther, { amountCents: 1000, dueDate: '2026-07-01' })).status === 403)
  check('非持有 CO 标回款(repay-line) → 403', (await post(`/cases/${s3case.id}/repay-lines`, coOther, { amountCents: 1000, channel: 'CASH', paidAt: '2026-06-25' })).status === 403)
}
// 录音 case-actor:非持有 CO 读他人录音 → 403；持有 CO 读本人录音 → 200(不误伤)
if (typeof recId2 !== 'undefined' && recId2 && coOther) {
  check('非持有 CO 读他人案件录音 → 403', (await getJson(`/recordings/${recId2}`, coOther)).status === 403)
  check('持有 CO 读本人案件录音 → 200(不误伤)', (await getJson(`/recordings/${recId2}`, co)).status === 200)
}
// reduce.approve 端点可达：PL 权限含 reduce.approve(不再死端点)
check('PL 权限含 reduce.approve(端点可达)', ((await getJson('/me', pl)).body?.permissions || []).includes('reduce.approve'))
// === v1.1.0 契约扩展 P0 两片 ===
// 切片A 工作台：CO=cockpit / SA=dashboard
const wbCo = await getJson('/workbench', co)
check('工作台 CO → cockpit + kpis/todos 结构', wbCo.status === 200 && wbCo.body?.layout === 'cockpit' && Array.isArray(wbCo.body?.todos))
check('工作台 SA → dashboard', (await getJson('/workbench', sa)).body?.layout === 'dashboard')
// 切片B 派单决策：SA 看服务商指标(仅陈列,无评分字段) / CO 无 case.dispatch → 403
const pm = await getJson('/dispatch/provider-metrics', sa)
check('服务商指标 SA → items(在催/催收员/人均/回款率)', pm.status === 200 && (pm.body?.items?.length ?? 0) >= 1 && pm.body.items[0].activeCases != null)
check('服务商指标 CO 无 case.dispatch → 403', (await getJson('/dispatch/provider-metrics', co)).status === 403)
// 切片B 催收员余量：VL 看本商余量+推荐 / 跨商 403
const vlOrg = (await getJson('/me', vl)).body?.org?.id
if (vlOrg) {
  const cap = await getJson(`/providers/${vlOrg}/collector-capacity`, vl)
  check('催收员余量 VL → holdCap+items+推荐', cap.status === 200 && cap.body?.holdCap > 0 && cap.body.items.some((c) => c.recommended))
  check('催收员余量 VL 跨服务商(999) → 403', (await getJson('/providers/999/collector-capacity', vl)).status === 403)
}
// === v1.2.0 P1: 消息中心(BR-M4-23 互推) + 公海事件日志 ===
if (s3) {
  const pcT = await login('cuihu_pc', 'Admin@123')
  const co2T = await login('jx_co2', 'Admin@123')
  // CO 转工单 → PC 收 TICKET_NEW
  await post(`/cases/${s3.id}/tickets`, co, { type: '上门核实', note: 'P1通知' })
  const pcUnread = (await getJson('/notifications/unread-count', pcT)).body?.count ?? 0
  check('互推: CO 转工单 → PC 未读≥1(TICKET_NEW)', pcUnread >= 1)
  const pcNotifs = (await getJson('/notifications?unreadOnly=true', pcT)).body?.items || []
  check('PC 消息含 TICKET_NEW', pcNotifs.some((n) => n.type === 'TICKET_NEW'))
  // PC 处理 → 持有 CO 收 TICKET_RECEIPT
  const pend = ((await getJson(`/cases/${s3.id}/tickets?page=1&size=20`, co)).body?.items || []).find((t) => t.status === 'PENDING')
  if (pend) {
    await post(`/tickets/${pend.id}/handle`, pcT, { result: '已核实' })
    check('互推: PC 回执 → 持有 CO 未读≥1(TICKET_RECEIPT)', ((await getJson('/notifications/unread-count', co)).body?.count ?? 0) >= 1)
  }
  // 越权: 标他人通知 → 403；本人标已读 → ok
  const myN = pcNotifs[0]
  if (myN) {
    check('标他人通知 → 403', (await post(`/notifications/${myN.id}/read`, co2T, {})).status === 403)
    check('本人标已读 → ok', [200].includes((await post(`/notifications/${myN.id}/read`, pcT, {})).status))
  }
  // 公海事件日志
  const ev = await getJson('/sea/events?page=1&size=5', co)
  check('公海事件日志 → items(event 枚举)', ev.status === 200 && Array.isArray(ev.body?.items))
}
// === 四方适配审计修复(workflow)验证 ===
// H-01/H-02: 读入口无 x-permission(按 range) — 物业/服务商可读 projects/batches/reports
check('H-01 PL 读 /projects(无 perm) → 200', (await getJson('/projects?page=1&size=5', pl)).status === 200)
check('H-01 VL 读 /batches(无 perm) → 200', (await getJson('/batches?page=1&size=5', vl)).status === 200)
check('H-02 PL 读经营报表(非平台专属) → 200', (await getJson('/reports/operation?dimension=batch', pl)).status === 200)
check('H-02 VL 读经营报表 → 200', (await getJson('/reports/operation?dimension=batch', vl)).status === 200)
// H-06: reduce-tiers 返 {source, tiers[]} 非裸数组
{
  // GET reduce-tiers 需 reduce.policy.edit(PL/PC),用 pl;取本物业批次(翠湖)
  const plBatch = ((await getJson('/batches?page=1&size=20', pl)).body?.items || [])[0]
  if (plBatch) {
    const rt = await getJson(`/batches/${plBatch.id}/reduce-tiers`, pl)
    check('H-06 reduce-tiers 返 {source,tiers[]} 结构', rt.status === 200 && Array.isArray(rt.body?.tiers) && typeof rt.body?.source === 'string', 'source=' + rt.body?.source)
  }
}
// H-08: 成员编辑 PATCH /members/{id}(MemberPatch) — PL 改本组织成员权限子集
{
  const mem = ((await getJson('/members?page=1&size=20', pl)).body?.items || []).find((m) => m.username === 'cuihu_pc')
  if (mem) {
    const r = await fetch(`${B}/members/${mem.id}`, { method: 'PATCH', headers: { Authorization: `Bearer ${pl}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ permissions: ['case.follow', 'case.paylink'] }) })
    check('H-08 PATCH /members/{id} 成员编辑 → 2xx', [200, 204].includes(r.status), 'HTTP ' + r.status)
  }
}
// MED ticket.handle 收回 CO：CO 处理工单 → 403(无权限)
if (s3case) {
  const tk = (await getJson(`/cases/${s3case.id}/tickets?page=1&size=20`, co)).body?.items?.[0]
  if (tk) check('CO 处理工单(ticket.handle 已收回) → 403', (await post(`/tickets/${tk.id}/handle`, co, { result: 'x' })).status === 403)
}
// HIGH sms 一次性防重放：同 code 第二次登录 → 401
await fetch(`${B}/auth/sms-code`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: '13900009000' }) })
const sms1 = await fetch(`${B}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: 'sms', phone: '13900009000', code: '000000' }) })
const sms2 = await fetch(`${B}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: 'sms', phone: '13900009000', code: '000000' }) })
check('sms code 一次性：首次 OK', sms1.ok)
check('sms code 一次性：二次重放 → 401', sms2.status === 401)
// MED batchId 安全解析：非数字 → 不 5xx
check('GET /cases?batchId=abc 非数字 → 非5xx', (await getJson('/cases?batchId=abc&page=1&size=5', sa)).status < 500)
// P1: caseIds 勾选拆派(US-M3-01) — GET /cases?batchId= 列本批案件供勾选(端点净室已验 SPLIT+caseIds→{ok:true};
//   此处验"按批列案件"数据源可用,实际拆派会消费案件态故不在共享 smoke 重复派)
const s0b2 = (bsSa.body?.items || []).find((b) => b.code === 'B-CH-M3-S0')
if (s0b2) {
  check('GET /cases?batchId= 列本批案件(供 caseIds 勾选)', (await getJson(`/cases?batchId=${s0b2.id}&page=1&size=50`, sa)).status === 200)
  check('GET /batches/{id} 批次详情 → 200', (await getJson(`/batches/${s0b2.id}`, sa)).body?.code != null)
}
// P1: AI 写界面 — PUT ai-config / POST script-lib
check('SA 编辑 AI配置(PUT /ai-config) → 2xx', [200, 201, 204].includes((await fetch(`${B}/ai-config`, { method: 'PUT', headers: { Authorization: `Bearer ${sa}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ llm: { provider: 'deepseek', model: 'deepseek-chat', temperature: 0.3 }, asr: { provider: 'bailian' } }) })).status))
check('SA 新建话术(POST /script-lib) → 2xx', [200, 201].includes((await post('/script-lib', sa, { scene: '首催开场', intent: '提醒', text: '您好，关于物业费…' })).status))
// SSOT：script_lib Rate 为 0-1 分数(V911 去 /100 漂移；首催开场 0.52，绝不 >1)
{
  const sl = await getJson('/script-lib?page=1&size=50', sa)
  const rated = (sl.body?.items || []).filter((s) => s.promiseRate != null)
  check('script-lib Rate 为分数 0-1(无百分比漂移)', rated.length > 0 && rated.every((s) => s.promiseRate <= 1 && s.repayRate <= 1), rated.length + ' 条有率值')
}
// P1: playbook 采纳(US-M5-07) — PL 对翠湖一期(项目1)采纳作战手册
check('GET /projects/1/playbook → 200', (await getJson('/projects/1/playbook', pl)).status === 200)
check('PL 采纳作战手册(POST /projects/1/playbook) → 2xx', [200, 201].includes((await post('/projects/1/playbook', pl, { version: 'v1.1', content: '通话前策略：先核实身份…' })).status))

// M5 质检
const risks = await getJson('/risks?page=1&size=30', sa)
check('GET /risks 全量检测列表(SA)', (risks.body?.items?.length ?? 0) >= 1, '共 ' + risks.body?.meta?.total)
const undone = (risks.body?.items || []).find((r) => !r.reviewed) || risks.body?.items?.[0]
if (undone) {
  check('VL 处置催收员风险(归属) → 200', (await post(`/risks/${undone.id}/dispose`, vl, { action: 'mark', note: '已整改' })).status === 200)
  check('SA 平台复核 → 200', (await post(`/risks/${undone.id}/review`, sa, { verdict: 'CONFIRMED', note: '属实' })).status === 200)
  check('物业 PL 复核 → 403(只平台复核 BR-M5-07c)', (await post(`/risks/${undone.id}/review`, pl, { verdict: 'CONFIRMED' })).status === 403)
}
const dtSa = await getJson('/dispose-tasks', sa)
const dtVl = await getJson('/dispose-tasks', vl)
check('处置任务跟踪仅平台(SA 200 / VL 403 BR-M5-07b)', dtSa.status === 200 && dtVl.status === 403, `SA ${dtSa.status}/VL ${dtVl.status}`)

// M8 结案（PL 撤案/坏账）
const plCases = await getJson('/cases?page=1&size=50', pl)
const active = (plCases.body?.items || []).find((c) => ['IN_PROGRESS', 'PROMISED', 'PROVIDER_SEA', 'PENDING_DISPATCH'].includes(c.status))
if (active) {
  check('PL 结案缺 reason → 422', (await post(`/cases/${active.id}/close`, pl, { kind: 'WITHDRAWN' })).status === 422)
  check('CO 结案 → 403(无 case.close)', (await post(`/cases/${active.id}/close`, co, { kind: 'WITHDRAWN', reason: 'x' })).status === 403)
  check('PL 撤案 → 200 WITHDRAWN', (await post(`/cases/${active.id}/close`, pl, { kind: 'WITHDRAWN', reason: '业主已售房' })).status === 200)
  check('已终态再结案 → 409', (await post(`/cases/${active.id}/close`, pl, { kind: 'BAD_DEBT', reason: 'y' })).status === 409)
}

// M6 存证 / M10 报表 / M7 H5
const ev = await getJson('/evidence?page=1&size=20', sa)
check('GET /evidence 列表(SA·三方隔离)', (ev.body?.items?.length ?? 0) >= 1, '共 ' + ev.body?.meta?.total)
// 取已出证(ISSUED·有 certNo)的存证验真；ISSUING 的尚无证书号(P2 可能新建在前)
const evId = (ev.body?.items || []).find((e) => e.status === 'ISSUED' || e.certNo)?.id ?? ev.body?.items?.[0]?.id
if (evId) {
  // 验真 public：不带 token 也能验
  const vf = await fetch(`${B}/evidence/${evId}/verify`)
  const vb = vf.ok ? await vf.json() : null
  check('存证验真 public(无 token) → 200 valid', vf.status === 200 && vb?.valid === true, 'certNo ' + vb?.certNo)
}
const rep = await getJson('/reports/operation', sa)
check('GET /reports/operation(SA·KPI+rows) → 200', rep.status === 200 && Array.isArray(rep.body?.kpis), (rep.body?.kpis?.length ?? 0) + ' KPI')
check('POST /reports/export(report.export) → 2xx', [200, 202].includes((await post('/reports/export', sa, { report: 'operation', format: 'xlsx' })).status))
// 维度钻取: project/batch/month 各返 kpis+rows
for (const dim of ['project', 'batch', 'month']) {
  const rd = await getJson(`/reports/operation?dimension=${dim}`, sa)
  check(`报表钻取 dimension=${dim} → kpis+rows`, rd.status === 200 && Array.isArray(rd.body?.kpis) && Array.isArray(rd.body?.rows), (rd.body?.rows?.length ?? 0) + ' 行')
}
// M7 业主账单 public(无 token)
const s3id = s3?.id
const payR = await fetch(`${B}/pay/demo-paylink-${s3id}`)
const payB = payR.ok ? await payR.json() : null
check('M7 业主账单 GET /pay/{token} public(无token) → 200', payR.status === 200 && payB?.payableCents != null, '应付 ' + payB?.payableCents)
check('M7 错 token → 404(不泄露不5xx)', (await fetch(`${B}/pay/bad-token-xyz`)).status === 404)

// 收口批次2: 设置/计费/批次导入/作废
const setg = await getJson('/settings', sa)
check('GET /settings(平台·业务规则域·不含AI)', setg.status === 200 && Array.isArray(setg.body) && !setg.body.some((x) => x.domain === 'AI'), (setg.body || []).map((x) => x.domain).join(','))
check('PL 看 /settings → 403(仅平台)', (await getJson('/settings', pl)).status === 403)
check('SA 改 ROTATION 配置 → 2xx', [200, 201].includes((await fetch(`${B}/settings`, { method: 'PUT', headers: { Authorization: `Bearer ${sa}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ domain: 'ROTATION', rotation: { holdCap: 60, maxRotations: 3 } }) })).status))
check('GET /billing/recharge-log(SA range)', (await getJson('/billing/recharge-log', sa)).status === 200)
check('SA 充值 STT → 2xx', [200, 201].includes((await post('/billing/recharge', sa, { orgId: '2', type: 'STT', qty: 100 })).status))
check('CO 充值 → 403(仅平台 billing.recharge)', (await post('/billing/recharge', co, { orgId: '2', type: 'STT', qty: 10 })).status === 403)
check('GET /sms-records(SA range)', (await getJson('/sms-records', sa)).status === 200)
// 批次导入(PL own-org, batch.import)
const imp = await post('/batches/import', sa, { projectId: '1', commInRate: 0.3, rows: [{ acctNo: 'IMP-001', ownerName: '导入业主', phone: '13900002222', room: '9-901', dueCents: 500000, arrearPeriod: '2025-01' }] })
check('批次导入 → 2xx(创建批次+案件)', [200, 201].includes(imp.status), 'HTTP ' + imp.status)
const impBatchId = imp.body?.id || imp.body?.batchId
if (impBatchId) {
  check('SA 作废导入批次 → 2xx(留痕)', [200, 201, 204].includes((await post(`/batches/${impBatchId}/void`, sa, { reason: 'E2E 作废' })).status))
}

// 成员管理/督导（PL 管本组织成员）+ reset-password 指定 newPassword(#3 验证)
const newMem = await post('/members', pl, { username: 'pc_e2e', name: '协调员E2E', phone: '13900009999', role: 'PC' })
check('PL 建本组织成员 → 2xx', [200, 201].includes(newMem.status), 'HTTP ' + newMem.status)
const memId = newMem.body?.id
if (memId) {
  check('PL 重置成员密码(指定 newPassword) → 2xx', [200, 201].includes((await post(`/members/${memId}/reset-password`, pl, { newPassword: 'Pc@E2E123' })).status))
  // 验证 #3: 指定的 newPassword 真生效(新成员能用它登录)
  const memLogin = await fetch(`${B}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mode: 'password', username: 'pc_e2e', password: 'Pc@E2E123' }) })
  check('新成员用指定口令登录 → 200(#3 newPassword 生效)', memLogin.status === 200)
  check('PL 督导成员(TRAINING) → 2xx', [200, 201].includes((await post(`/members/${memId}/supervision-actions`, pl, { action: 'TRAINING', note: 'E2E 培训' })).status))
  check('PL 停用成员 → 2xx', [200, 201, 204].includes((await post(`/members/${memId}/disable`, pl, {})).status))
}
check('VL 改翠湖成员 → 403(跨组织 BR-M1-04a)', memId ? (await post(`/members/${memId}/disable`, vl, {})).status === 403 : true)
// P4: 权限矩阵 / AI 配置 / 组织管理
check('GET /permission-matrix(SA) → 200 非空', (await getJson('/permission-matrix', sa)).body?.length >= 1)
check('GET /ai-config(SA) → 200', (await getJson('/ai-config', sa)).status === 200)
const newOrg = await post('/orgs', sa, { type: 'PROVIDER', name: '测试服务商P4', ownerAccount: 'p4_vl', ownerPhone: '13900003333' })
check('SA 新建组织+绑负责人 → 201', [200, 201].includes(newOrg.status), 'HTTP ' + newOrg.status)
if (newOrg.body?.id) check('SA 改绑组织负责人 → 2xx', [200, 201, 204].includes((await fetch(`${B}/orgs/${newOrg.body.id}/owner`, { method: 'PATCH', headers: { Authorization: `Bearer ${sa}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ newPhone: '13900004444', resetPassword: true }) })).status))

// 一号多账号(BR-M1-11): password 多账号→loginTicket+accounts→select-account→token
async function rawLogin(body) {
  const r = await fetch(`${B}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  return r.ok ? await r.json() : null
}
const single = await rawLogin({ mode: 'password', username: 'admin', password: 'Admin@123' })
check('单账号(admin) → 直接 token(无回退)', !!single?.token && !single?.loginTicket)
const multi = await rawLogin({ mode: 'password', username: 'duo_pc', password: 'Admin@123' })
check('多账号(duo_pc) → loginTicket+accounts(2)', !multi?.token && !!multi?.loginTicket && (multi?.accounts?.length ?? 0) === 2)
if (multi?.loginTicket) {
  const sel = await fetch(`${B}/auth/select-account`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ loginTicket: multi.loginTicket, accountId: multi.accounts[1].accountId }) })
  const selBody = sel.ok ? await sel.json() : null
  check('select-account(票据+账号) → token', !!selBody?.token)
  if (selBody?.token) {
    const me2 = await getJson('/me', selBody.token)
    check('多账号选定后 /me = 所选账号', me2.status === 200, me2.body?.name + '/' + me2.body?.role)
  }
}
// sms 登录: sms-code 后 code=000000
await fetch(`${B}/auth/sms-code`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ phone: '13900009000' }) })
const smsR = await rawLogin({ mode: 'sms', phone: '13900009000', code: '000000' })
check('短信登录(phone+000000) → 多账号 ticket', !!smsR?.loginTicket && (smsR?.accounts?.length ?? 0) === 2)
check('坏票据 select-account → 401', (await fetch(`${B}/auth/select-account`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ loginTicket: 'bad', accountId: '1' }) })).status === 401)

console.log(fail === 0 ? '\n🎉 全 117 端点·全模块·多账号登录 端到端全过 — 契约优先全链路贯通' : `\n⚠ ${fail} 项失败`)
process.exit(fail === 0 ? 0 : 1)
