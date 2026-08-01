import { test, expect } from './fixtures/test'
import { loginAs, loginRole } from './helpers'

// BR-M3-29 公海可见性收敛（v1.18.0 开放抢单停用后两侧均无开放池分段）。三条产品规则,三组断言:
//   ① 平台方只有平台公海——**服务商公海/开放池分段不存在**(明细是服务商内务,平台靠 T1/T2 预警);
//   ② 服务商侧只有待接单+本商公海——**平台公海/开放池分段不存在**;
//   ③ 物业角色无公海概念——菜单无入口、直敲 URL 被守卫弹回(后端还有 sea.view 403 兜底,那层由后端测试钉)。
test.describe('BR-M3-29 公海可见性收敛', () => {
  test('SA 平台侧:案件运营→平台公海 Tab,池分段默认平台公海、不含「服务商公海/开放抢单池」', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    await page.locator('.segctrl').first().getByText('平台公海').click()
    // SeaView 的池分段控件（含「平台公海」项且带 on 态；开放池分段已随 v1.18.0 停用移除）
    const poolSeg = page.locator('.segctrl').nth(1)
    await expect(poolSeg.locator('span', { hasText: '平台公海' })).toHaveClass(/on/)
    await expect(poolSeg.locator('span', { hasText: '服务商公海' })).toHaveCount(0)
    await expect(page.getByText('开放抢单池')).toHaveCount(0)
  })

  test('VL 服务商侧:分段不含「平台公海」', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '案件公海' }).click()
    await expect(page).toHaveURL(/\/sea/)
    await expect(page.locator('.segctrl span', { hasText: '服务商公海' })).toHaveClass(/on/)
    await expect(page.locator('.segctrl span', { hasText: '平台公海' })).toHaveCount(0)
  })

  test('CO 催收员:分段同服务商侧,不含「平台公海」', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.getByRole('menuitem', { name: '案件公海' }).click()
    await expect(page.locator('.segctrl span', { hasText: '平台公海' })).toHaveCount(0)
  })

  test('PL 物业负责人:菜单无公海入口,直敲 /sea 被弹回工作台', async ({ page }) => {
    await loginAs(page, 'cuihu_pl')
    await expect(page.getByRole('menuitem', { name: /公海/ })).toHaveCount(0)
    await page.goto('/sea')
    await expect(page).not.toHaveURL(/\/sea/)   // 守卫拦截(e2e-nav-lint 口径标记)
    await expect(page).toHaveURL(/\/dashboard/)
  })

  test('PC 物业协调员:同 PL,公海概念不存在', async ({ page }) => {
    await loginAs(page, 'cuihu_pc')
    await expect(page.getByRole('menuitem', { name: /公海/ })).toHaveCount(0)
    await page.goto('/sea')
    await expect(page).not.toHaveURL(/\/sea/)   // 守卫拦截(e2e-nav-lint 口径标记)
    await expect(page).toHaveURL(/\/dashboard/)
  })
})
