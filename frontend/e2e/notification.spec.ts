import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// v1.2.0 P1：消息中心(BR-M4-23) + 公海事件日志(BR-M3-22) 真屏。
test.describe('v1.2.0 消息中心 + 公海事件', () => {
  test('消息中心页可达·互推通知列表就位', async ({ page }) => {
    await loginAs(page, 'cuihu_pc')                    // 协调员收工单通知
    await page.getByRole('button', { name: '消息' }).click()
    await expect(page).toHaveURL(/\/notifications/)
    await expect(page.getByText(/消息中心/)).toBeVisible()
    await expect(page.getByText('仅未读')).toBeVisible()
  })

  test('公海页实时事件日志面板就位', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.getByRole('menuitem', { name: '公海' }).click()
    await expect(page).toHaveURL(/\/sea/)
    await expect(page.getByText('实时事件日志')).toBeVisible()
  })
})
