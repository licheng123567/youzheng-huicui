import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// v1.19.0「额度管理」（计费明细 + 充值中心合并·以组织为聚合）。
// 交互（用户定）：平台先看**组织列表**（一行一组织·四类额度横向展开）→ 点组织进 /quota/:orgId
// 看该组织的用量明细与充值流水；物业/服务商在 /quota 直接看自己。
// 充值中心此前零 E2E 覆盖且按钮全是死的（无 @click、余额硬编码）——本 spec 是净增覆盖。
test.describe('v1.19.0 额度管理', () => {

  test('SA：组织列表（一行一组织）→ 点进详情 → 充值全链', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '额度管理' }).click()
    await expect(page).toHaveURL(/\/quota/)

    // 组织列表：一行一组织，四类额度横向列
    const t = page.locator('table').first()
    // v1.20.0：法律文书不计费 → 额度体系只剩三类
    for (const h of ['组织', '录音转写余额', '短信余额', '存证余额', '本月总用量']) {
      await expect(t.locator('thead').getByText(h, { exact: true })).toBeVisible()
    }
    const row = t.locator('tbody tr', { hasText: '翠湖物业' }).first()
    await expect(row).toBeVisible()
    await expect(t.getByText('法律文书', { exact: false })).toHaveCount(0)   // v1.20.0 已从额度体系移除

    // 点组织 → 详情页
    await row.click()
    await expect(page).toHaveURL(/\/quota\/\d+/)
    await expect(page.getByText('返回组织列表', { exact: false })).toBeVisible()
    await expect(page.locator('.kpi')).toHaveCount(3)          // 三类余额卡（法律文书不计费）

    // 短信卡上的「充值」→ 抽屉 → 充 100 条
    await page.locator('.kpi', { hasText: '短信' }).getByText('充值', { exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText('充值 · 翠湖物业')).toBeVisible()
    await drawer.locator('.el-input-number input').fill('100')
    await drawer.getByRole('button', { name: '确认充值' }).click()
    await expect(page.getByText(/已充值 100条/)).toBeVisible()

    // 充值流水首行 +100
    await page.getByText('充值流水', { exact: true }).click()
    await expect(page.locator('table tbody tr').first()).toContainText('+100')
  })

  test('SE：组织列表可见；进详情后充值按钮不渲染（无 billing.recharge）', async ({ page }) => {
    await loginRole(page, 'SE')
    await page.getByRole('menuitem', { name: '额度管理' }).click()
    await page.locator('table tbody tr').first().click()
    await expect(page).toHaveURL(/\/quota\/\d+/)
    await expect(page.locator('.kpi')).toHaveCount(3)
    await expect(page.getByText('充值', { exact: true })).toHaveCount(0)   // 无充值入口
  })

  test('PL：/quota 直接看自己（无组织列表层）+ 线下充值提示', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '额度管理' }).click()
    await expect(page).toHaveURL(/\/quota/)
    await expect(page.getByText(/线下联系平台运营/)).toBeVisible()
    await expect(page.locator('.kpi')).toHaveCount(3)
    await expect(page.locator('table thead').getByText('组织', { exact: true })).toHaveCount(0)
  })

  test('VL：用量分析月/日切换 + 明细下钻穿透列（业主/项目/批次）', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '额度管理' }).click()

    const rows = page.locator('table tbody tr')
    await expect(rows.first()).toBeVisible()
    await expect(page.locator('table thead').getByText('月份')).toBeVisible()

    await page.getByText('按日', { exact: true }).click()
    await expect(page.locator('table thead').getByText('日期')).toBeVisible()

    await rows.first().getByText('查看明细', { exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText('用量明细', { exact: false })).toBeVisible()
    for (const h of ['业主', '房号', '项目', '批次']) {
      await expect(drawer.getByText(h, { exact: true })).toBeVisible()
    }
  })

  test('CO：无「额度管理」菜单，/quota 与老书签 /billing 均被守卫弹开', async ({ page }) => {
    await loginRole(page, 'CO')
    await expect(page.getByRole('menuitem', { name: '额度管理' })).toHaveCount(0)
    await page.goto('/quota')
    await expect(page).not.toHaveURL(/\/quota/)
    await page.waitForLoadState('networkidle')
    await page.goto('/billing')
    await expect(page).not.toHaveURL(/\/billing/)
  })
})
