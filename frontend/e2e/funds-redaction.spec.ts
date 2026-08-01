import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// BR-M1-06/BR-M9-11 资金双线·整列脱敏（裁列而非占位）。
//
// 口径注意（易错）：批次详情的费率标签是**角色相对命名**——同一个 commInRate，
// 平台标「收佣比例」、物业标「付佣比例」（物业是付钱方）。故不能按标签文案断言隔离，
// 只能按「渲染出几条费率」断言：平台双线=2 条，物业/服务商单线=1 条。
// 字段级隔离本身由服务端保证（smoke: comm-status 三角色裁剪；Gate1: schema 一致性）。
//
// 可达性（constants/nav.ts 单一真源）：
//   /projects  → SA/SE/PL/PC/VL 有菜单；CO 无（案件入口是私海/公海）。
//   /batches   → 仅 SA/SE（撮合派单）；PL/PC/VL 经「案件管理」批次优先入口下钻 /batches/{id}。

test.describe('BR-M9-11 资金双线·服务商不见收佣比例', () => {
  test('VL /projects 列表无「收佣比例」列头（整列裁掉，非占位）', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.goto('/projects')
    await expect(page.locator('table').first()).toBeVisible()
    await expect(page.locator('thead').getByText('收佣比例')).toHaveCount(0)
    await expect(page.getByText('物业视角不可见')).toHaveCount(0)
  })

  test('CO 无「项目管理」菜单，直敲 /projects 被路由守卫拦截', async ({ page }) => {
    await loginRole(page, 'CO')
    await expect(page.getByRole('menuitem', { name: '项目管理' })).toHaveCount(0)
    await page.goto('/projects')
    await expect(page).not.toHaveURL(/\/projects/)
  })
})

test.describe('BR-M9-11 资金双线·物业不见付佣比例', () => {
  for (const role of ['PL', 'PC'] as const) {
    test(`${role} 无「撮合派单」菜单，但可从案件管理下钻批次详情`, async ({ page }) => {
      await loginRole(page, role)
      await expect(page.getByRole('menuitem', { name: '撮合派单' })).toHaveCount(0)
      // 裸列表页仍不可达（撮合派单是平台专属）
      await page.goto('/batches')
      await expect(page).not.toHaveURL(/\/batches$/)
      // 但菜单内的「案件管理」是批次优先入口，点批次行可进详情（PL 在此提案收佣比例）
      await page.getByRole('menuitem', { name: '案件管理' }).click()
      await expect(page).toHaveURL(/\/cases/)
      await page.locator('tbody tr.row-click').first().click()
      await expect(page).toHaveURL(/\/batches\/\d+/)
    })

    test(`${role} 批次详情只渲染一条费率（物业侧无 payOutRate）`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/cases')
      await page.locator('tbody tr.row-click').first().click()
      await expect(page).toHaveURL(/\/batches\/\d+/)
      // 物业单线：仅 commInRate 一条费率标签（标签文案按角色相对命名，不据其断言隔离）
      await expect(page.locator('.tag.war, .tag.suc').filter({ hasText: /比例/ })).toHaveCount(1)
    })
  }
})

test.describe('D5/BR-M9-11 平台双线全含(回归保护)', () => {
  test('SA /batches 详情·收佣+付佣两条费率均渲染且带百分比', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/batches')
    // 必须锚定**已派单**批次（未派单的 payOutRate 为空 → 只渲染一条费率）。
    // 不能取首行：smoke/导入用例会在列表顶部造出未派单的新批次（如 B12），令首行不确定。
    const row = page.locator('tbody tr').filter({ hasText: 'B-CH-2026-01' }).first()
    await expect(row).toBeVisible()
    // BatchesView 无 row-click：详情入口=批次号 a.link（无 href，故无 link role，按 class 定位）
    await row.locator('a.link').first().click()
    await expect(page).toHaveURL(/\/batches\/\d+/)
    const rateTags = page.locator('.tag.war, .tag.suc').filter({ hasText: /比例/ })
    await expect(rateTags).toHaveCount(2)
    await expect(rateTags.first()).toContainText('%')
  })

  test('SA /batches 列表双线列头均在', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/batches')
    await expect(page.locator('thead').getByText('收佣比例')).toBeVisible()
    await expect(page.locator('thead').getByText('付佣比例')).toBeVisible()
  })
})
