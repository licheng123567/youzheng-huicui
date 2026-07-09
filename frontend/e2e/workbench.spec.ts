import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// US-M4 催收作业台 + US-M9 结算：故事级真屏（进案件作业台见动作 tab；结算见对账/支付申请单）。
test.describe('US-M4 催收作业台(CO)', () => {
  test('CO 进案件列表→打开案件→作业台 tab 就位', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.getByRole('menuitem', { name: '案件管理' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('tbody tr')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    // 作业台中栏 tab 是 .dtabs .t 普通 div（非 role=tab）；催收员见 作战手册/协调员处理/沟通记录/项目资料
    const tabs = page.locator('.dtabs .t')
    await expect(tabs.filter({ hasText: '作战手册' })).toBeVisible()
    await expect(tabs.filter({ hasText: '沟通记录' })).toBeVisible()
  })
})

test.describe('US-M9 结算(平台)', () => {
  test('SA 进结算→对账汇总与支付申请单区就位', async ({ page }) => {
    await loginAs(page, 'admin')
    await page.getByRole('menuitem', { name: '收佣对账' }).click()
    await expect(page).toHaveURL(/\/settlement/)
    await expect(page.getByText('对账汇总')).toBeVisible()
    await expect(page.getByText(/支付申请单/).first()).toBeVisible()
  })
})
