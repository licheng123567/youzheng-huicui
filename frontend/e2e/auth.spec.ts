import { test, expect } from '@playwright/test'
import { loginAs, DEV_PW } from './helpers'

// US-M1 登录/多账号：真屏验证登录三态（口令单账号、一号多账号选择、短信）。
test.describe('US-M1 登录与多账号', () => {
  test('口令登录(admin 单账号)直登工作台', async ({ page }) => {
    await loginAs(page, 'admin')
    await expect(page).toHaveURL(/\/dashboard/)
    await expect(page.getByRole('menuitem', { name: '当前主体' })).toBeVisible()  // 导航就位
  })

  test('一号多账号(duo_pc)→出现选择→选定身份进工作台', async ({ page }) => {
    await page.goto('/login')
    await page.getByPlaceholder(/用户名/).fill('duo_pc')
    await page.getByPlaceholder(/口令/).fill(DEV_PW)
    await page.getByRole('button', { name: '登录' }).click()
    await expect(page.getByText(/该手机关联多个账号/)).toBeVisible()  // 多账号选择出现
    const choices = page.getByRole('button', { name: /多账号·/ })
    await expect(choices.first()).toBeVisible()
    await choices.first().click()
    await expect(page).not.toHaveURL(/\/login/)
    await expect(page.getByRole('menuitem', { name: '当前主体' })).toBeVisible()
  })

  test('短信登录(13900009000+000000)→多账号选择', async ({ page }) => {
    await page.goto('/login')
    await page.getByText('短信登录', { exact: true }).click()  // el-radio-button(非 button role)
    await page.getByPlaceholder(/手机号/).fill('13900009000')
    await page.getByRole('button', { name: /获取验证码/ }).click()
    await page.getByPlaceholder(/验证码/).fill('000000')
    await page.getByRole('button', { name: '登录' }).click()
    await expect(page.getByText(/该手机关联多个账号/)).toBeVisible()
  })

  test('未登录访问受保护页→重定向登录', async ({ page }) => {
    await page.context().clearCookies()
    await page.goto('/cases')
    await expect(page).toHaveURL(/\/login/)
  })
})
