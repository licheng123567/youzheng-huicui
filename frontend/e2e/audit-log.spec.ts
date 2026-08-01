import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// P-ORG-08 / US-M1 操作日志可见 + BR-M1-15 代操作留痕 + BR-M1-08 审计可见范围门控。
// 侧栏菜单标签与页面卡片标题均为「操作日志」(constants/nav.ts PATH2LABEL 单一真源)。
test.describe('P-ORG-08 操作日志(管理角色)', () => {
  for (const role of ['SA', 'PL'] as const) {
    test(`${role} 进审计日志→列表渲染·表头时间/操作人/动作/范围就位`, async ({ page }) => {
      await loginRole(page, role)
      await page.getByRole('menuitem', { name: '操作日志' }).click()
      await expect(page).toHaveURL(/\/audit-log/)
      await expect(page.locator('.card .t', { hasText: '操作日志' })).toBeVisible()
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

test.describe('BR-M1-08 审计范围门控(CO 无操作日志入口)', () => {
  // 操作日志是管理角色功能：NAV_BY_ROLE.CO 不含 audit（菜单按原型每角色 nav 1:1）。
  // 菜单即路由白名单单一真源(allowedPaths)，故 CO 既无入口，直接敲 URL 也被守卫弹回。
  test('CO 侧栏无「操作日志」入口，且直敲 /audit-log 被路由守卫拦截', async ({ page }) => {
    await loginRole(page, 'CO')
    await expect(page.getByRole('menuitem', { name: '操作日志' })).toHaveCount(0)
    await page.goto('/audit-log')
    await expect(page).not.toHaveURL(/\/audit-log/)
  })
})
