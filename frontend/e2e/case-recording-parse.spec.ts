import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M5 余额不足暂停→充值补解析(BR-M5-02)：
// QUOTA_BLOCKED 录音出「补解析」按钮→/recordings/{id}/parse 转 PARSING；
// 余额不足 409 提示；批量补解析→/recordings/batch-parse 返 queued/skipped。
test.describe('US-M5 余额不足补解析(CO)', () => {
  async function openCallTab(page: any) {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
  }

  test('QUOTA_BLOCKED→显示「补解析」按钮', async ({ page }) => {
    await openCallTab(page)
    const blocked = page.getByText('QUOTA_BLOCKED')
    if (!(await blocked.count())) {
      test.skip(true, '本案录音非 QUOTA_BLOCKED 态')
    }
    await expect(page.getByRole('button', { name: '补解析', exact: true })).toBeVisible()
  })

  test('点补解析→PARSING 或余额不足提示', async ({ page }) => {
    await openCallTab(page)
    const btn = page.getByRole('button', { name: '补解析', exact: true })
    if (!(await btn.count())) {
      test.skip(true, '无补解析入口')
    }
    await btn.click()
    // 成功受理(解析中) 或 余额不足提示二选一
    await expect(
      page.getByText(/已受理补解析|解析中|余额不足/).first(),
    ).toBeVisible()
  })

  test('批量补解析→入队/跳过反馈', async ({ page }) => {
    await openCallTab(page)
    const btn = page.getByRole('button', { name: '批量补解析' })
    if (!(await btn.count())) {
      test.skip(true, '无批量补解析入口')
    }
    await btn.click()
    await expect(
      page.getByText(/已受理批量补解析|入队|余额不足/).first(),
    ).toBeVisible()
  })
})
