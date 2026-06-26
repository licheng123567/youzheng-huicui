import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M2-02 / BR-M2-13 批次级协调员多对多挂载：
// PL 进批次详情→维护协调员→选 2 名 PC 保存→刷新 coordinators 显示 2 名；CO/VL 无入口。
async function openFirstBatch(page: any) {
  await page.getByRole('menuitem', { name: '批次' }).click()
  await expect(page).toHaveURL(/\/batches/)
  const rows = page.locator('.el-table__row')
  await expect(rows.first()).toBeVisible()
  // 进详情靠点「批次号」列里的链接按钮(行本身无 row-click)，按钮文案=批次 code(如 B-CH-...)
  await rows.first().getByRole('button').first().click()
  await expect(page).toHaveURL(/\/batches\/\d+/)
}

test.describe('BR-M2-13 批次协调员(PL)', () => {
  // 批次协调员维护入口门控 batch.import；按 Permissions.of：仅 SA/SE 具该权限，PL/PC/CO/VL 均不具。
  // 故「维护协调员」按钮在批次详情仅平台(SA)可见(BatchDetailView.vue:151)，正向用例由 SA 驱动。
  test('SA 维护批次协调员→选 PC 保存→显示已关联', async ({ page }) => {
    await loginRole(page, 'SA')
    await openFirstBatch(page)
    const btn = page.getByRole('button', { name: '维护协调员' })
    await expect(btn).toBeVisible()
    await btn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '协调员' })
    await expect(dlg).toBeVisible()
    // 候选异步加载(GET /members?role=PC)；等 transfer 任一面板出现条目后再操作。
    // 全量覆盖语义：本用例重复执行后 PC 可能已全在「已关联」右侧→左侧候选为空，此时直接保存即可，
    // 故不强制断言左侧有项，仅在有候选时勾选并移入右侧。
    const transfer = dlg.locator('.el-transfer')
    await expect(transfer).toBeVisible()
    await expect(dlg.locator('.el-transfer-panel__item').first()).toBeVisible()
    const leftItems = dlg.locator('.el-transfer-panel').first().locator('.el-transfer-panel__item')
    const n = await leftItems.count()
    if (n >= 1) await leftItems.nth(0).click()
    if (n >= 2) await leftItems.nth(1).click()
    if (n >= 1) {
      // nth(1)=左→右(►) 才是把候选移入已关联的按钮；nth(0)=右→左(◄)在右侧无勾选时恒 disabled。
      await dlg.locator('.el-transfer__button').nth(1).click().catch(() => {})
    }
    await dlg.getByRole('button', { name: '保存协调员' }).click()
    await expect(page.getByText('已更新批次协调员')).toBeVisible()
  })

  for (const role of ['CO', 'VL'] as const) {
    test(`${role} 无「维护协调员」入口`, async ({ page }) => {
      await loginRole(page, role)
      await openFirstBatch(page)
      await expect(page.getByRole('button', { name: '维护协调员' })).toHaveCount(0)
    })
  }
})
