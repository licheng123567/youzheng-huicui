import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// 派单即一处同定双佣（撮合设定·平台最终决定权）：
//   收佣=物业公司支付平台（IN，物业可提案、平台确认）；付佣=平台支付服务商（OUT）；防倒挂 付佣≤收佣。
//   提交先 PUT /commission-rates（置 comm_in_confirmed=true）再 dispatch。
test.describe('派单双佣（撮合设定·平台最终决定权）', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    await expect(page).toHaveURL(/\/batches/)
    // 只有未派批次渲染「派单」入口（不绑定具体批次号——其他 spec 可能已把某批派掉）
    await page.locator('a.btn.txt').filter({ hasText: /^派单$/ }).first().click()
  })

  test('派单抽屉含双佣区：收佣(物业→平台)+付佣(平台→服务商)+毛利', async ({ page }) => {
    const drawer = page.getByRole('dialog')
    await expect(drawer.getByText('佣金比例（撮合设定 · 平台最终决定权）')).toBeVisible()
    await expect(drawer.getByText(/收佣比例.*物业公司支付平台/)).toBeVisible()
    await expect(drawer.getByText('收佣比例(%)')).toBeVisible()
    await expect(drawer.getByText('付佣比例(%)')).toBeVisible()
    await expect(drawer.getByText('平台毛利', { exact: true })).toBeVisible()
    // 预填批次既定收佣（种子 30%）：两个比率输入框有值
    const numbers = drawer.locator('.el-input-number input')
    await expect(numbers).toHaveCount(2)
    await expect(numbers.first()).not.toHaveValue('')
  })

  test('防倒挂：付佣 > 收佣 → 红警且提交禁用', async ({ page }) => {
    const drawer = page.getByRole('dialog')
    // 付佣输入框（第二个 el-input-number）设为超过收佣的值
    const numbers = drawer.locator('.el-input-number input')
    await numbers.nth(1).fill('90')
    await numbers.nth(1).blur()
    await expect(drawer.getByText(/防倒挂/).last()).toBeVisible()
    await expect(drawer.getByRole('button', { name: /确认双佣并派单/ })).toBeDisabled()
  })
})
