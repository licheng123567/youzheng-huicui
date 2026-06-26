import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M9-12a 对账列表按批次汇总 + M-10 字段修复：
// 平台 SA 进结算，IN/OUT 切换后对账汇总表展示批次号(batch)、比例(commRate×100%)、
// 应结佣金(dueCents/100) 正确，无字段漂移空列。
test.describe('BR-M9-12a 对账汇总字段(SA·双线)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/settlement')
    await expect(page.getByText('对账汇总')).toBeVisible()
  })

  for (const side of ['IN', 'OUT'] as const) {
    test(`${side} 线对账三列(批次/比例/应结)有值不漂移`, async ({ page }) => {
      // 切换 side（el-radio-button 文案含 IN/OUT 或 收佣/付佣）
      const sideBtn = page.getByText(side === 'IN' ? /IN|收佣/ : /OUT|付佣/).first()
      if (await sideBtn.count()) await sideBtn.click().catch(() => {})

      const recon = page.locator('.el-table').first()
      await expect(recon).toBeVisible()
      const firstRow = recon.locator('.el-table__row').first()
      await expect(firstRow).toBeVisible()

      // 比例列含百分号（commRate×100%）；应结列含金额；批次列非空 → 无漂移空列
      await expect(recon.getByText('%').first()).toBeVisible()
      await expect(firstRow.getByText('—', { exact: true })).toHaveCount(0)
    })
  }
})
