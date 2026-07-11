import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// v1.17.0 批次结项+承接历史（用户拍板：结项=全部收回+承诺保留）。
// 只读断言为主（结项是不可逆写动作，真实收回链路由后端 curl/E2E 脚本覆盖）：
// 已派批次有「结项」入口 → 确认框出 收回统计+原因+备注；「承接历史」抽屉出每段表现。
test.describe('v1.17.0 批次结项(SA·案件运营)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    await expect(page).toHaveURL(/\/batches/)
  })

  test('批次运营表双 KPI 列 + 已派批次有结项/承接历史入口', async ({ page }) => {
    const t = page.locator('table').first()
    for (const h of ['项目', '户数', '应收', '已收', '回款率', '状态分布', '服务商']) {
      await expect(t.locator('thead').getByText(h, { exact: true })).toBeVisible()
    }
    // 种子批次 B-CH-2026-01 已派捷信催收 → 行内有结项与承接历史
    const row = t.locator('tbody tr', { hasText: 'B-CH-2026-01' })
    await expect(row.getByText('结项', { exact: true })).toBeVisible()
    await expect(row.getByText('承接历史', { exact: true })).toBeVisible()
  })

  test('结项确认框：收回统计+原因下拉+备注必填', async ({ page }) => {
    const row = page.locator('tbody tr', { hasText: 'B-CH-2026-01' })
    await row.getByText('结项', { exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText('终止服务商承接')).toBeVisible()
    await expect(drawer.getByText('合计收回')).toBeVisible()
    await expect(drawer.getByText('结项原因')).toBeVisible()
    // 备注为空点确认 → 前端拦截（备注必填），批次不受影响
    await drawer.getByRole('button', { name: '确认结项并收回' }).click()
    await expect(page.getByText(/结项备注必填/)).toBeVisible()
    await drawer.getByRole('button', { name: '取消' }).click()
  })

  test('承接历史抽屉：每段 服务商/期间回款/期间回款率', async ({ page }) => {
    const row = page.locator('tbody tr', { hasText: 'B-CH-2026-01' })
    await row.getByText('承接历史', { exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText(/第 1 任/)).toBeVisible()
    await expect(drawer.getByText('期间回款', { exact: false }).first()).toBeVisible()
    await expect(drawer.getByText('期间回款率', { exact: false }).first()).toBeVisible()
  })
})
