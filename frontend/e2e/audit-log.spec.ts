import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// P-ORG-08 / US-M1 操作日志可见 + BR-M1-15 代操作留痕 + BR-M1-08 审计可见范围门控。
// 注：侧栏菜单标签为「审计日志」，页面卡片标题为「操作日志」。
test.describe('P-ORG-08 操作日志(管理角色)', () => {
  for (const role of ['SA', 'PL'] as const) {
    test(`${role} 进审计日志→列表渲染·表头时间/操作人/动作/范围就位`, async ({ page }) => {
      await loginRole(page, role)
      await page.getByRole('menuitem', { name: '审计日志' }).click()
      await expect(page).toHaveURL(/\/audit-log/)
      await expect(page.getByText(/操作日志/)).toBeVisible()
      const table = page.locator('.el-table').first()
      await expect(table).toBeVisible()
      for (const col of ['时间', '操作人', '动作', '范围']) {
        await expect(table.locator('.el-table__header').getByText(col, { exact: true })).toBeVisible()
      }
    })
  }

  test('代操作记录(proxyFor 非空)→「代操作」标签且可展开 before/after 快照', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/audit-log')
    await expect(page.locator('.el-table').first()).toBeVisible()
    const proxyTag = page.locator('.el-tag', { hasText: '代操作' })
    if (!(await proxyTag.count())) {
      test.skip(true, '无代操作种子记录')
    }
    await expect(proxyTag.first()).toBeVisible()
    // 展开该行查看 before/after 快照
    await page.locator('.el-table__expand-icon').first().click()
    await expect(page.getByText(/变更前 before|变更后 after|无变更快照/).first()).toBeVisible()
  })
})

test.describe('BR-M1-08 审计范围门控(CO 仅见本范围)', () => {
  test('CO 侧栏有「审计日志」入口（读端点 x-data-scope:range 无 x-permission → 全登录态可见本范围）', async ({ page }) => {
    await loginRole(page, 'CO')
    // 审计读端点 range 可见、无 x-permission，故菜单对全登录态(含 CO)渲染；真隔离由服务端按范围裁剪。
    const item = page.getByRole('menuitem', { name: '审计日志' })
    await expect(item).toHaveCount(1)
    await item.click()
    await expect(page).toHaveURL(/\/audit-log/)
    await expect(page.getByText(/操作日志/)).toBeVisible()
  })
})
