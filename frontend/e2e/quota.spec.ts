import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// v1.19.0「额度管理」（计费明细 + 充值中心合并·以组织为聚合）。
// 充值中心此前零 E2E 覆盖且按钮全是死的（无 @click、余额硬编码）——本 spec 是净增覆盖。
test.describe('v1.19.0 额度管理', () => {

  test('SA：组织额度总览 + 充值全链（余额与流水随之变化）', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '额度管理' }).click()
    await expect(page).toHaveURL(/\/quota/)

    // 组织额度总览：一行=组织×额度类型
    const t = page.locator('table').first()
    for (const h of ['组织', '额度类型', '余额', '本月用量', '上月用量']) {
      await expect(t.locator('thead').getByText(h, { exact: true })).toBeVisible()
    }
    // 翠湖物业(PROPERTY)的短信行可充值
    const smsRow = t.locator('tbody tr', { hasText: '翠湖物业' }).filter({ hasText: '短信' }).first()
    await expect(smsRow).toBeVisible()

    // 充值 100 条
    await smsRow.getByText('充值', { exact: true }).click()
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText('后台充值能力额度')).toBeVisible()
    await drawer.locator('.el-input-number input').fill('100')
    await drawer.getByRole('button', { name: '确认充值' }).click()
    await expect(page.getByText(/已充值 100条/)).toBeVisible()

    // 充值流水首行出现 +100
    await page.getByText('充值流水', { exact: true }).click()
    const logRow = page.locator('table tbody tr').first()
    await expect(logRow).toContainText('+100')
    await expect(logRow).toContainText('翠湖物业')
  })

  test('SE：同屏只读——充值按钮禁用（无 billing.recharge）', async ({ page }) => {
    await loginRole(page, 'SE')
    await page.getByRole('menuitem', { name: '额度管理' }).click()
    await expect(page).toHaveURL(/\/quota/)
    await expect(page.locator('table').first()).toBeVisible()
    // 页首「+ 充值」按钮不渲染；行内充值按钮 disabled
    await expect(page.getByRole('button', { name: '+ 充值' })).toHaveCount(0)
    const btn = page.locator('button', { hasText: /^充值$/ }).first()
    await expect(btn).toBeDisabled()
  })

  test('PL：只见本组织余额卡 + 线下充值提示，无充值按钮', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '额度管理' }).click()
    await expect(page).toHaveURL(/\/quota/)
    await expect(page.getByText(/线下联系平台运营/)).toBeVisible()
    await expect(page.getByRole('button', { name: '+ 充值' })).toHaveCount(0)
    // 四张余额卡（STT/SMS/EVIDENCE/LEGAL）
    await expect(page.locator('.kpi')).toHaveCount(4)
  })

  test('VL：用量分析月/日切换 + 明细下钻穿透列（业主/项目/批次）', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '额度管理' }).click()
    await page.getByText('用量分析', { exact: true }).click()

    // 种子：捷信 STT 5 笔跨两月
    const rows = page.locator('table tbody tr')
    await expect(rows.first()).toBeVisible()
    await expect(page.locator('table thead').getByText('月份')).toBeVisible()

    // 切按日
    await page.getByText('按日', { exact: true }).click()
    await expect(page.locator('table thead').getByText('日期')).toBeVisible()

    // 明细下钻：穿透列（原 billing-sms.spec 三级下钻断言迁移至此）
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
    await page.goto('/billing')
    await expect(page).not.toHaveURL(/\/billing/)
  })
})
