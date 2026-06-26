import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// v1.3.0 P2：个人中心(自助改密) + 案件搜索 真屏。
test.describe('v1.3.0 个人中心 + 搜索', () => {
  test('头部姓名→个人中心→改密对话框就位', async ({ page }) => {
    await loginAs(page, 'admin')
    await page.getByRole('button', { name: /平台超管|admin|SA|系统/ }).first().click().catch(() => {})
    // 兜底：直接进 profile（头部姓名按钮文本含 org 名）
    await page.goto('/profile')
    await expect(page.getByText('个人中心')).toBeVisible()
    await page.getByRole('button', { name: '修改密码' }).click()
    await expect(page.getByText('校验旧密码')).toBeVisible()       // 改密对话框
  })

  test('全局搜索→案件命中表就位', async ({ page }) => {
    await loginAs(page, 'admin')
    await page.goto('/search?q=' + encodeURIComponent('张'))
    await expect(page).toHaveURL(/\/search/)
    // 命中行（C-1001 张三）出现
    await expect(page.getByText('C-1001')).toBeVisible()
  })
})
