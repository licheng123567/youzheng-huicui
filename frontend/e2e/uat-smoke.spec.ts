import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

test.describe('UAT seed smoke', () => {
  test('organization management shows the seeded Cuihu owner contact', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/org-mgmt')

    const cuihuRow = page.locator('table tbody tr').filter({ hasText: '翠湖物业' }).first()
    await expect(cuihuRow.locator('[data-field="owner-username"]')).toHaveText('cuihu_pl')
    await expect(cuihuRow.locator('[data-field="owner-phone"]')).toHaveText('13900000001')
  })

  test('members only shows seeded platform staff', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/members')

    const memberTable = page.locator('table').first()
    const rows = memberTable.locator('tbody tr')
    const adminRow = rows.filter({ hasText: 'admin' })
    const operatorRow = rows.filter({ hasText: 'plat_se' })

    await expect(adminRow).toContainText('13800000000')
    await expect(operatorRow).toContainText('13800000001')
    await expect(rows.filter({ hasText: 'cuihu_pl' })).toHaveCount(0)
    await expect(rows.filter({ hasText: 'jx_vl' })).toHaveCount(0)
    await expect(rows.filter({ hasText: 'jx_co1' })).toHaveCount(0)
  })
})
