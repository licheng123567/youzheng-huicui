import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// 服务商负责人(VL)三处 UI 还原（2026-07-10 用户验收回报的缺口）：
//   ① 工作台要有「团队即时看板」(US-M10-03)——每个催收员的实时作业状态，此前 VL 只看到自己的待办；
//   ② 「案件公海」页要有「待接单」tab(BR-M3-03a·批次粒度)——此前 S1 待接单混在服务商公海里冒充已接单；
//   ③ 付佣对账页「回款明细/支付申请单」要能点开——此前路由指向存根,按钮绑的是空函数。
test.describe('服务商负责人 UI 还原', () => {
  test('工作台：团队即时看板就位，逐催收员一行', async ({ page }) => {
    await loginRole(page, 'VL')
    await expect(page.getByText('团队即时看板（US-M10-03）')).toBeVisible()
    const board = page.locator('.card', { hasText: '团队即时看板' })
    await expect(board.locator('th', { hasText: '今日动作' })).toBeVisible()
    await expect(board.locator('th', { hasText: '今日回款' })).toBeVisible()
    await expect(board.locator('tbody tr').first()).toBeVisible()
  })

  test('案件公海：待接单 tab 存在且按批次分组', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '案件公海' }).click()
    await expect(page).toHaveURL(/\/sea/)
    await page.locator('.segctrl span', { hasText: '待接单' }).click()
    // 种子数据里有平台派给捷信的待接单案件；批次行带「接单（承接）」动作
    await expect(page.getByText('平台派单（整批/拆单）到本服务商后需')).toBeVisible()
  })

  test('付佣对账：回款明细与支付申请单点击有响应（弹窗打开）', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '收佣对账' }).click()
    await expect(page).toHaveURL(/\/settlement-out/)
    // BR-M9-12e 统一支付逻辑：支付申请单由**平台**生成,VL 只读——生成入口对 VL 不渲染
    await expect(page.getByRole('button', { name: /生成支付申请单/ })).toHaveCount(0)
    await expect(page.getByText('支付申请单由平台生成')).toBeVisible()
    // 对账汇总行的两个下钻按钮——此前是 () => {} 空函数
    await page.locator('tbody tr').first().getByRole('button', { name: '回款明细' }).click()
    await expect(page.getByText('缴款日期').first()).toBeVisible()
    await page.keyboard.press('Escape')
    await page.locator('tbody tr').first().getByRole('button', { name: '支付申请单' }).click()
    await expect(page.getByText(/单号|暂无支付申请单/).first()).toBeVisible()
  })

  test('对照：平台(SA)有付佣组单入口（BR-M9-12e 平台组单·v1.16.0 双线总账）', async ({ page }) => {
    // v1.16.0：/settlement-out 对平台已并入 /settlement 双线总账；组单入口 = 未收/未付可点标签。
    await loginRole(page, 'SA')
    await page.goto('/settlement-out')
    await expect(page).toHaveURL(/\/settlement$/)
    await expect(page.getByText('平台双线总账')).toBeVisible()
    // 付佣列存在（表头）且任一「未付」标签可点开付佣组单抽屉（种子批次有未付明细）
    await expect(page.locator('table thead').getByText('未付', { exact: true })).toBeVisible()
    await page.locator('button:has(.tag.war)').first().click()
    await expect(page.getByRole('dialog').getByText(/生成(收佣支付申请单|付佣支付单)/)).toBeVisible()
  })
})
