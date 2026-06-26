import { Page, expect } from '@playwright/test'

export const DEV_PW = 'Admin@123'

/** 口令登录（单账号直登）：返回后停在工作台（非 /login）。 */
export async function loginAs(page: Page, username: string, password = DEV_PW) {
  await page.goto('/login')
  await page.getByPlaceholder(/用户名/).fill(username)
  await page.getByPlaceholder(/口令/).fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login/, { timeout: 10_000 })
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

/** 断言侧栏某菜单项可见 / 不可见（UX 门控验证）。 */
export async function expectMenu(page: Page, label: string, visible: boolean) {
  const item = page.getByRole('menuitem', { name: label })
  if (visible) await expect(item).toBeVisible()
  else await expect(item).toHaveCount(0)
}
