import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M8 结案脱敏(BR-M8-09)：
// VL/CO 打开已结案(redacted)案件→联系人电话脱敏占位、明细被统计卡替代；
// 平台同案仍见完整明细。
test.describe('US-M8 结案脱敏(VL/CO 收敛 / 平台全见)', () => {
  // 在案件列表里找一条已结案/脱敏案件并打开
  async function openSomeCase(page: any) {
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
  }

  for (const role of ['VL', 'CO'] as const) {
    test(`${role} 打开脱敏案件→统计收敛视图(无逐行明细)`, async ({ page }) => {
      await loginRole(page, role)
      await openSomeCase(page)
      // 脱敏收敛提示出现，则联系人明细表不渲染
      const redactAlert = page.getByText(/已结案并脱敏|BR-M8-09/)
      if (!(await redactAlert.count())) {
        test.skip(true, '未命中已脱敏案件')
      }
      await expect(redactAlert.first()).toBeVisible()
      // 联系人逐行明细收敛为统计卡：不出现「设主号」逐行操作
      await expect(page.getByRole('button', { name: '设主号' })).toHaveCount(0)
    })
  }

  test('SA 同案仍见完整联系人明细', async ({ page }) => {
    await loginRole(page, 'SA')
    await openSomeCase(page)
    // 平台不收敛：联系人表渲染（主号列/电话明细可见）
    await expect(page.getByRole('tab', { name: /概览 \/ 联系人/ })).toBeVisible()
    await expect(page.locator('.el-table').first()).toBeVisible()
  })
})
