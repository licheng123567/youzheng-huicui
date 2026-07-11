import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// v1.17.0 三合一「案件运营」：撮合派单+案件管理+平台公海（用户决定·保留一个菜单入口）。
test.describe('SA 案件运营三合一', () => {
  test('SA 无独立「平台公海」菜单;案件运营页有 批次运营/平台公海 两 Tab', async ({ page }) => {
    await loginRole(page, 'SA')
    // 平台公海不再是独立菜单项
    await expect(page.getByRole('menuitem', { name: '平台公海' })).toHaveCount(0)
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    await expect(page).toHaveURL(/\/batches/)
    // 页级 Tab 两个
    const tabs = page.locator('.segctrl').first()
    await expect(tabs.getByText('批次运营')).toBeVisible()
    await expect(tabs.getByText('平台公海')).toBeVisible()
    // 默认批次运营:批次表在
    await expect(page.getByText('批次（催收单）')).toBeVisible()
    // 切平台公海:内嵌 SeaView 的开放抢单池分段出现
    await tabs.getByText('平台公海').click()
    await expect(page.locator('.segctrl', { hasText: '开放抢单池' })).toBeVisible()
  })
})
