import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// US-M9-09 催收员提成自查(只读 BR-M9-19a)。
// 口径归位：CO 的提成自查在「我的业绩」(/my-stats，数据源 GET /me/stats)，不在结算页。
// NAV_BY_ROLE.CO 不含任何对账/佣金菜单，菜单即路由白名单(allowedPaths)，故 CO 进不了 /settlement。
test.describe('US-M9-09 催收员提成自查(CO·只读)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'CO')
  })

  test('CO 侧栏无任何对账/佣金菜单，直敲 /settlement 被路由守卫拦截', async ({ page }) => {
    for (const label of ['收佣对账', '付佣对账', '催收员佣金']) {
      await expect(page.getByRole('menuitem', { name: label })).toHaveCount(0)
    }
    await page.goto('/settlement')
    await expect(page).not.toHaveURL(/\/settlement/)
  })

  test('CO 我的业绩→提成汇总三宫格 + 按批次结算 tab', async ({ page }) => {
    await page.getByRole('menuitem', { name: '我的业绩' }).click()
    await expect(page).toHaveURL(/\/my-stats/)
    // 提成汇总（全时段累计）：累计/已结/待结。
    // 「已结提成/待结提成」同时是按批次表格的列头 → 必须限定在 .kpi 内，否则 strict mode 命中 2 个。
    for (const kpi of ['累计提成（全部）', '已结提成', '待结提成']) {
      await expect(page.locator('.kpi').filter({ hasText: kpi })).toBeVisible()
    }
    // 按批次结算三 tab（与催收员分支同构）；tab 文案后跟计数徽标 → 不能用 exact 文本匹配
    const tabs = page.locator('.dtabs .t')
    await expect(tabs.filter({ hasText: '待结算' })).toBeVisible()
    await expect(tabs.filter({ hasText: '已结算完毕' })).toBeVisible()
  })

  test('全只读：我的业绩无生成/确认/撤销等写按钮', async ({ page }) => {
    await page.goto('/my-stats')
    await expect(page.locator('.kpi').filter({ hasText: '累计提成（全部）' })).toBeVisible()
    await expect(page.getByRole('button', { name: /生成支付申请单|生成佣金单/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /确认收款|确认支付/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /撤销/ })).toHaveCount(0)
  })
})
