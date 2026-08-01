import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// 存证管理(矩阵§7 line128) PL=○只读/PC=●/VL·CO=— ；H-02 修复验收。
// PL 可见 /evidence 只读列表+下载证书；PC 可见且可发起存证；
// VL/CO /evidence 菜单不渲染、直访被 scope 裁剪为空；SA/SE 可见全量。
test.describe('存证菜单与可见性门控(H-02)', () => {
  test('PL 可见存证·只读列表+下载证书入口', async ({ page }) => {
    await loginRole(page, 'PL')
    await expect(page.getByRole('menuitem', { name: '存证管理' })).toBeVisible()
    await page.goto('/evidence')
    await expect(page.getByText(/存证/).first()).toBeVisible()
    // 只读视图提示，无发起入口（创建在案件作业台）
    await expect(page.getByRole('button', { name: /发起存证|出证/ })).toHaveCount(0)
  })

  test('PC 可见存证（具创建口径）', async ({ page }) => {
    await loginRole(page, 'PC')
    await expect(page.getByRole('menuitem', { name: '存证管理' })).toBeVisible()
    await page.goto('/evidence')
    await expect(page.locator('.card').first()).toBeVisible()
  })

  // 服务商侧（VL/CO）三方隔离：存证不对服务商开放（BR-M6）。
  // NAV_BY_ROLE 无 evidence 项 → 无菜单入口；菜单即路由白名单 → 直敲 /evidence 也被守卫弹回。
  // 服务端 scope 裁剪（返回空集）仍由 smoke/Gate1 覆盖，此处验 UI 层不可达。
  for (const role of ['VL', 'CO'] as const) {
    test(`${role} 无「存证管理」菜单，直访 /evidence 被路由守卫拦截`, async ({ page }) => {
      await loginRole(page, role)
      await expect(page.getByRole('menuitem', { name: '存证管理' })).toHaveCount(0)
      await page.goto('/evidence')
      await expect(page).not.toHaveURL(/\/evidence/)
    })
  }

  test('SA 可见全量存证列表', async ({ page }) => {
    await loginRole(page, 'SA')
    await expect(page.getByRole('menuitem', { name: '存证管理' })).toBeVisible()
    await page.goto('/evidence')
    await expect(page.locator('tbody tr').first()).toBeVisible()
  })
})
