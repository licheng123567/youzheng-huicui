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
check('GET /batches 返回 1 批次, 平台视角双线均含', bs.body?.items?.length === 1 && bs.body.items[0].payOutRate != null, bs.body?.items?.[0]?.code)
const cs = await getJson('/cases?page=1&size=20', sa)
check('GET /cases 返回 3 案件', cs.body?.items?.length === 3)
const cd = await getJson(`/cases/${cs.body.items[0].id}`, sa)
check('GET /cases/{id} 聚合 CaseDetail(case+contacts)', !!cd.body?.case && Array.isArray(cd.body?.contacts), cd.body?.case?.ownerName)
// 资金双线：翠湖 PL 看批次应无 payOutRate（物业视角）
const bsPl = await getJson('/batches?page=1&size=20', pl)
check('翠湖 PL 看批次: 物业视角无 payOutRate(资金双线)', bsPl.body?.items?.[0]?.payOutRate == null && bsPl.body?.items?.[0]?.commInRate != null, 'viewRole=' + bsPl.body?.items?.[0]?.viewRole)

console.log(fail === 0 ? '\n🎉 M2 纵向切片端到端全过' : `\n⚠ ${fail} 项失败`)
process.exit(fail === 0 ? 0 : 1)
