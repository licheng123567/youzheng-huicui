import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// v1.16.0 平台双线总账（GET /recon/rollup-dual）：
// SA 进「结算对账」（收佣/付佣两菜单已合并为一条），批次总账一行同时给
// 收佣(应收/已收/未收) + 付佣(应付/已付/未付) + 毛利；「明细」抽屉出案件级双线收付佣状态。
test.describe('v1.16.0 平台批次双线总账(SA)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/settlement')
    await expect(page.getByText('平台双线总账')).toBeVisible()
    await page.waitForLoadState('networkidle')
  })

  test('菜单只有一条「结算对账」，无收佣/付佣独立入口', async ({ page }) => {
    await expect(page.getByRole('menuitem', { name: '结算对账' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '收佣对账' })).toHaveCount(0)
    await expect(page.getByRole('menuitem', { name: '付佣对账' })).toHaveCount(0)
  })

  test('总账表双线列头齐全且首行有值', async ({ page }) => {
    const t = page.locator('table').first()
    await expect(t).toBeVisible()
    for (const h of ['应收', '已收', '未收', '应付', '已付', '未付', '毛利', '收佣%', '付佣%']) {
      await expect(t.locator('thead').getByText(h, { exact: true })).toBeVisible()
    }
    const firstRow = t.locator('tbody tr').first()
    await expect(firstRow).toBeVisible()
    await expect(t.getByText('%').first()).toBeVisible()
  })

  test('「明细」抽屉出案件级双线收付佣状态列', async ({ page }) => {
    await page.locator('table tbody tr').first().getByText('明细', { exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText('案件收付佣明细')).toBeVisible()
    await expect(drawer.getByText('收佣状态')).toBeVisible()
    await expect(drawer.getByText('付佣状态')).toBeVisible()
    await expect(drawer.getByText('承接服务商').first()).toBeVisible()
  })

  test('平台老书签 /settlement-out 重定向回 /settlement', async ({ page }) => {
    await page.goto('/settlement-out')
    await expect(page).toHaveURL(/\/settlement$/)
    await expect(page.getByText('平台双线总账')).toBeVisible()
  })

  test('「单据」跳支付申请单 Tab', async ({ page }) => {
    await page.locator('table tbody tr').first().getByText('单据', { exact: true }).click()
    await expect(page.getByText('收佣单(IN·向物业)')).toBeVisible()
    await expect(page.locator('table tbody tr').first()).toBeVisible()
  })
})
