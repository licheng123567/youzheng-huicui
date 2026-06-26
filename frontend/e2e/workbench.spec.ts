import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// US-M4 催收作业台 + US-M9 结算：故事级真屏（进案件作业台见动作 tab；结算见对账/支付申请单）。
test.describe('US-M4 催收作业台(CO)', () => {
  test('CO 进案件列表→打开案件→作业台 tab 就位', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await expect(page.getByRole('tab', { name: /通话 \/ AI 复盘/ })).toBeVisible()  // 作业台 tab
    await expect(page.getByRole('tab', { name: /承诺 \/ 工单/ })).toBeVisible()
  })
})

test.describe('US-M9 结算(平台)', () => {
  test('SA 进结算→对账汇总与支付申请单区就位', async ({ page }) => {
    await loginAs(page, 'admin')
    await page.getByRole('menuitem', { name: '结算' }).click()
    await expect(page).toHaveURL(/\/settlement/)
    await expect(page.getByText('对账汇总')).toBeVisible()
    await expect(page.getByText(/支付申请单/).first()).toBeVisible()
  })
})
