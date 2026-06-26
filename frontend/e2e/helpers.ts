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
