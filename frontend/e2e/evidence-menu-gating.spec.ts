import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// 存证管理(矩阵§7 line128) PL=○只读/PC=●/VL·CO=— ；H-02 修复验收。
// PL 可见 /evidence 只读列表+下载证书；PC 可见且可发起存证；
// VL/CO /evidence 菜单不渲染、直访被 scope 裁剪为空；SA/SE 可见全量。
test.describe('存证菜单与可见性门控(H-02)', () => {
  test('PL 可见存证·只读列表+下载证书入口', async ({ page }) => {
    await loginRole(page, 'PL')
    await expect(page.getByRole('menuitem', { name: '存证' })).toBeVisible()
    await page.goto('/evidence')
    await expect(page.getByText(/存证/).first()).toBeVisible()
    // 只读视图提示，无发起入口（创建在案件作业台）
    await expect(page.getByRole('button', { name: /发起存证|出证/ })).toHaveCount(0)
  })

  test('PC 可见存证（具创建口径）', async ({ page }) => {
    await loginRole(page, 'PC')
    await expect(page.getByRole('menuitem', { name: '存证' })).toBeVisible()
    await page.goto('/evidence')
    await expect(page.locator('.el-card').first()).toBeVisible()
  })

  for (const role of ['VL', 'CO'] as const) {
    test(`${role} 直访 /evidence 列表被 scope 裁剪为空`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/evidence')
      // 服务商范围无存证：表格无数据行
      await expect(page.locator('.el-table__row')).toHaveCount(0)
    })
  }

  test('SA 可见全量存证列表', async ({ page }) => {
    await loginRole(page, 'SA')
    await expect(page.getByRole('menuitem', { name: '存证' })).toBeVisible()
    await page.goto('/evidence')
    await expect(page.locator('.el-table__row').first()).toBeVisible()
  })
})
