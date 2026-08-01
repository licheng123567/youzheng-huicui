import { Page, expect } from '@playwright/test'
import { randomUUID } from 'node:crypto'

// UAT 必须注入独立随机口令；本地/CI 未注入时保持既有 dev 默认值。
export const DEV_PW = process.env.UAT_DEV_PASSWORD || 'Admin@123'

/** 口令登录（单账号直登）：返回后停在工作台（非 /login）。 */
export async function loginAs(page: Page, username: string, password = DEV_PW) {
  await page.goto('/login')
  await page.getByPlaceholder(/账号名|用户名/).fill(username)
  await page.getByPlaceholder(/密码|口令/).fill(password)
  const workbenchResponse = page.waitForResponse((response) => {
    const path = new URL(response.url()).pathname
    return response.request().method() === 'GET' && path === '/v1/workbench'
  }, { timeout: 15_000 })
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await expect(page).not.toHaveURL(/\/login/, { timeout: 10_000 })
  await (await workbenchResponse).finished()
  await page.waitForLoadState('networkidle')
}

/** 退出（清 token 回登录）。 */
export async function logout(page: Page) {
  await page.evaluate(() => localStorage.removeItem('token'))
}

/**
 * 角色 → 种子账号（DevSeeder 单一来源；PL/PC/VL/CO 账号均由 DevSeeder 在 dev profile 种入）。
 * SA=平台超管 / PL=翠湖物业负责人 / PC=翠湖协调员 / VL=捷信负责人 / CO=捷信催收员甲。
 * 取数据最丰富的一套，供各 spec 直接 loginAs(page, ACCOUNTS.PL) 等。
 */
export const ACCOUNTS = {
  SA: 'admin',
  SE: 'plat_se',        // 平台运营（种子有此账号，此前未登记 → loginRole(page,'SE') 会 fill(undefined)）
  PL: 'cuihu_pl',
  PC: 'cuihu_pc',
  VL: 'jx_vl',
  CO: 'jx_co1',
  CO2: 'jx_co2',
} as const

export type RoleKey = keyof typeof ACCOUNTS

/** 按角色键登录（如 loginRole(page, 'PL')）。 */
export async function loginRole(page: Page, role: RoleKey, password = DEV_PW) {
  await loginAs(page, ACCOUNTS[role], password)
}

/** 清理上一角色令牌后，以指定角色重新登录。 */
export async function switchRole(page: Page, role: RoleKey, password = DEV_PW) {
  await page.waitForLoadState('networkidle', { timeout: 5_000 }).catch(() => undefined)
  if (/^https?:/.test(page.url())) await page.evaluate(() => localStorage.clear())
  await loginRole(page, role, password)
}

/** 使用当前登录令牌调用同源 API；失败断言不输出响应体，避免把敏感字段带入日志。 */
export async function authJson(
  page: Page,
  method: string,
  path: string,
  body?: unknown,
  expected = [200],
) {
  const idempotencyKey = ['POST', 'PUT', 'PATCH'].includes(method.toUpperCase())
    ? randomUUID()
    : undefined
  const result = await page.evaluate(
    async ({ method, path, body, idempotencyKey }) => {
      const token = localStorage.getItem('token')
      if (!token) return { authenticated: false, status: 0, responseText: '' }
      const response = await fetch(path, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      return {
        authenticated: true,
        status: response.status,
        responseText: await response.text(),
      }
    },
    { method, path, body, idempotencyKey },
  )
  expect(result.authenticated, 'authenticated token').toBe(true)
  expect(expected, `${method} ${path} returned ${result.status}`).toContain(result.status)
  const responseText = result.responseText
  return responseText ? JSON.parse(responseText) : null
}

/** 断言侧栏某菜单项可见 / 不可见（UX 门控验证）。 */
export async function expectMenu(page: Page, label: string, visible: boolean) {
  const item = page.getByRole('menuitem', { name: label })
  if (visible) await expect(item).toBeVisible()
  else await expect(item).toHaveCount(0)
}

/**
 * 按户号(acctNo)打开案件详情。
 * 走全局搜索 /search（通用页，全角色可达；后端按 业主/房号/户号/电话 匹配 + range 裁剪）——
 * 不能走「案件管理」：管理角色那里是**批次**优先列表；催收员的「我的案件」扁平表也**没有户号列**，
 * 按 acctNo 过滤行永远匹配不到。
 */
export async function openCaseByAcctNo(page: Page, acctNo: string) {
  await page.goto(`/search?q=${acctNo}`)
  const rows = page.locator('tbody tr.row-click')
  await expect(rows.first()).toBeVisible()
  await rows.first().click()
  await expect(page).toHaveURL(/\/cases\/\d+/)
}

/**
 * 进批次详情（角色相关的两条入口，constants/nav.ts 单一真源）：
 *   平台 SA/SE → 菜单「撮合派单」列表 → 点批次号 a.link（无 href 故无 link role）。
 *   PL/PC/VL   → 无撮合派单菜单；走菜单「案件管理」批次优先入口 → 点批次行（tr.row-click）。
 *   CO         → 案件入口是私海/公海扁平清单，无批次详情可达（本函数不支持）。
 * code 省略则取首行；给定 code 时按批次号精确定位那一行。
 * tab='props' 时切到「批次属性」——协调员/减免档位/作战手册/佣金提案都在该 tab（默认 tab 是「案件明细」）。
 */
export async function openBatchDetail(page: Page, role: RoleKey, code?: string, tab: 'cases' | 'props' = 'cases') {
  if (role === 'CO') throw new Error('CO 无批次详情入口（案件入口为私海/公海扁平清单）')
  if (role === 'SA' || role === 'SE') {
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    await expect(page).toHaveURL(/\/batches/)
    const row = code
      ? page.locator('tbody tr').filter({ hasText: code }).first()
      : page.locator('tbody tr').first()
    await expect(row).toBeVisible()
    await row.locator('a.link').first().click()
  } else {
    await page.getByRole('menuitem', { name: '案件管理' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const row = code
      ? page.locator('tbody tr.row-click').filter({ hasText: code }).first()
      : page.locator('tbody tr.row-click').first()
    await expect(row).toBeVisible()
    await row.click()
  }
  await expect(page).toHaveURL(/\/batches\/\d+/)
  if (tab === 'props') {
    // tab 是普通 span（无 tab role），按文案点；点后等该 tab 的固定卡片标题出现。
    // 注意 .sec-title 里同时含标题文案与「+ 添加协调员」按钮，故不能用 exact 文本匹配。
    await page.getByText('批次属性', { exact: true }).click()
    await expect(page.locator('.sec-title', { hasText: '协调员（本批次）' })).toBeVisible()
  }
}
