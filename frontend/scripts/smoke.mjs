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
  check('CO 发缴费链接 → 2xx', [200, 201].includes((await post(`/cases/${s3.id}/pay-links`, co, { channel: 'SMS' })).status))
  const pr = await getJson(`/cases/${s3.id}/promises?page=1&size=20`, co)
  check('GET 承诺列表增长(≥2)', (pr.body?.items?.length ?? 0) >= 2, '共 ' + pr.body?.meta?.total)
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

console.log(fail === 0 ? '\n🎉 全模块 M1-M5+M8+M9 端到端全过 — 核心闭环含生命周期收尾' : `\n⚠ ${fail} 项失败`)
process.exit(fail === 0 ? 0 : 1)
