#!/usr/bin/env node
/**
 * E2E 导航口径对账（补 Playwright 的慢反馈盲区）。
 *
 * 背景：ds-admin 改版后菜单标签与角色可达路由集大改，34 个 spec 里的
 * 「某角色点某菜单 / 某角色 goto 某路径」大面积失效，但每条失败要烧 30s 超时才暴露。
 * 本检查直接拿 src/constants/nav.ts（菜单+路由白名单的单一真源）静态对账 e2e/*.spec.ts，
 * 秒级给出全部不一致，且能挡住「产品改了菜单、测试没跟」的回归。
 *
 * 用法: node scripts/e2e-nav-lint.mjs   (退出码 1 = 有不一致)
 */
import { readFileSync, readdirSync, mkdtempSync, rmSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const UNIVERSAL_PATHS = ['/dashboard', '/profile', '/search', '/notifications']
// e2e/helpers.ts ACCOUNTS 的反向表：loginAs('admin') → SA
const ACCOUNT_TO_ROLE = {
  admin: 'SA', cuihu_pl: 'PL', cuihu_pc: 'PC', jx_vl: 'VL',
  jx_co1: 'CO', jx_co2: 'CO', duo_pc: 'PC', duo_co: 'CO',
}

// nav.ts 是 TS，先编译成 ESM 再 import（不引第三方运行时，用 vite 自带的 esbuild）
const tmp = mkdtempSync(join(tmpdir(), 'navlint-'))
const navJs = join(tmp, 'nav.mjs')
execFileSync('npx', ['esbuild', join(ROOT, 'src/constants/nav.ts'), '--format=esm', `--outfile=${navJs}`, '--log-level=error'], { cwd: ROOT })
const { NAV_BY_ROLE, KEY2PATH, navLabel, isAllowedPath } = await import(pathToFileURL(navJs).href)
rmSync(tmp, { recursive: true, force: true })

/** 该角色侧栏实际渲染的菜单标签集（navLabel 做了按角色相对命名，如 PL 的 /settlement→付佣对账）。 */
function menuLabels(role) {
  const out = new Set()
  for (const item of NAV_BY_ROLE[role] ?? []) {
    if (typeof item === 'object') continue        // 分组标题
    const path = KEY2PATH[item]
    if (path) out.add(navLabel(path, role))
  }
  return out
}

const problems = []
const specDir = join(ROOT, 'e2e')

for (const file of readdirSync(specDir).filter((f) => f.endsWith('.spec.ts'))) {
  const lines = readFileSync(join(specDir, file), 'utf8').split('\n')
  let roles = []   // 当前作用域的角色（loginRole/loginAs 或 for(const role of [...]) 循环）
  const fileScreenLabels = []   // 本文件里「菜单文案 → 路由」常表收集到的 label

  lines.forEach((line, i) => {
    const byRole = line.match(/loginRole\(page,\s*'([A-Z]{2})'/)
    if (byRole) roles = [byRole[1]]
    const byAccount = line.match(/loginAs\(page,\s*'([a-z_0-9]+)'/)
    if (byAccount) roles = ACCOUNT_TO_ROLE[byAccount[1]] ? [ACCOUNT_TO_ROLE[byAccount[1]]] : []
    const loop = line.match(/for \(const role of \[([^\]]+)\]/)
    if (loop) roles = (loop[1].match(/'([A-Z]{2})'/g) ?? []).map((s) => s.replaceAll("'", ''))

    const at = `${file}:${i + 1}`

    // ① 点菜单：该角色菜单里必须真有这一项
    const click = line.match(/getByRole\('menuitem',\s*\{\s*name:\s*'([^']+)'\s*\}\)\.click\(\)/)
    if (click) {
      for (const role of roles) {
        if (!menuLabels(role).has(click[1])) {
          problems.push(`${at}  ${role} 点击菜单「${click[1]}」→ 该角色无此菜单项`)
        }
      }
    }

    // ①' 菜单文案常表（如 navigation.spec 的 `const SCREENS = [{ label: '项目管理', url: ... }]`）：
    //    这些 label 最终喂给 getByRole('menuitem')，但字面量不在 click 行上，① 抓不到。
    //    识别特征：同一行里既有 label 又有 url（路由断言），说明是「菜单文案 → 路由」表。
    const screen = line.match(/label:\s*'([^']+)'\s*,\s*url:/)
    if (screen) fileScreenLabels.push({ at, label: screen[1] })

    // ② goto：与路由守卫口径一致（紧邻的 not.toHaveURL 视为「预期被拦」）
    const goto = line.match(/page\.goto\('(\/[^']*)'\)/)
    if (goto) {
      const path = goto[1].split('?')[0]
      const skip = path === '/login' || path.startsWith('/pay/') || path.startsWith('/u/') || path.startsWith('/m')
      if (!skip) {
        const expectsBlocked = /not\.toHaveURL/.test((lines[i + 1] ?? '') + (lines[i + 2] ?? ''))
        for (const role of roles) {
          const allowed = isAllowedPath(role, path, UNIVERSAL_PATHS)
          if (!allowed && !expectsBlocked) problems.push(`${at}  ${role} goto ${path} → 守卫会拦截，但用例未预期`)
          if (allowed && expectsBlocked) problems.push(`${at}  ${role} goto ${path} → 用例预期被拦，实际放行`)
        }
      }
    }
  })

  // ①' 收尾：常表里的 label 对本文件登录过的每个角色都必须是真实菜单项。
  // （常表定义在文件顶部、登录发生在下方，故按文件维度统一校验，而非按行序作用域。）
  if (fileScreenLabels.length) {
    const src = lines.join('\n')
    const fileRoles = new Set([
      ...(src.match(/loginRole\(page,\s*'([A-Z]{2})'/g) ?? []).map((s) => s.slice(-3, -1)),
      ...(src.match(/loginAs\(page,\s*'([a-z_0-9]+)'/g) ?? [])
        .map((s) => ACCOUNT_TO_ROLE[s.match(/'([a-z_0-9]+)'/)[1]])
        .filter(Boolean),
    ])
    for (const role of fileRoles) {
      for (const { at, label } of fileScreenLabels) {
        if (!menuLabels(role).has(label)) {
          problems.push(`${at}  ${role} 菜单常表含「${label}」→ 该角色无此菜单项`)
        }
      }
    }
  }
}

if (problems.length) {
  console.error(`E2E 导航口径不一致 ${problems.length} 处：`)
  for (const p of problems) console.error(`  ❌ ${p}`)
  console.error('::error::e2e spec 与 src/constants/nav.ts（菜单+路由白名单单一真源）不一致')
  process.exit(1)
}
console.log('✅ e2e spec 的 角色×菜单 / 角色×goto 与 nav.ts 口径一致')
