import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// v1.1.0 契约扩展 P0：角色工作台(BR-M4-20) + 派单决策辅助(BR-M3-24) 真屏。
test.describe('v1.1.0 工作台 + 派单决策', () => {
  test('CO 工作台=今日驾驶舱(今日必办区就位)', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await expect(page).toHaveURL(/\/dashboard/)
    // cockpit 形态：左侧「今日必办」worklist（DashboardView 无「工作台 今日驾驶舱」这行文案）
    await expect(page.getByText('今日必办')).toBeVisible()
  })

  test('SA 工作台=仪表盘形态', async ({ page }) => {
    await loginAs(page, 'admin')
    // 管理角色=仪表盘：有「今日看板」，无驾驶舱的「今日必办」
    await expect(page.getByText(/今日看板/)).toBeVisible()
    await expect(page.getByText('今日必办')).toHaveCount(0)
  })

  test('SA 派单对话框→服务商指标决策辅助就位', async ({ page }) => {
    await loginAs(page, 'admin')
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    await expect(page).toHaveURL(/\/batches/)
    // 行内「派单」是 <a class="btn txt">（无 href → 无 link/button role），按 class+精确文案定位；
    // 同列还有「重派」，故用 exact text 过滤
    await page.locator('a.btn.txt').filter({ hasText: /^派单$/ }).first().click()
    await page.getByRole('button', { name: /加载各服务商指标/ }).click()
    await expect(page.getByText('近30天回款率')).toBeVisible()        // 指标表头
    await expect(page.getByText('捷信催收')).toBeVisible()
  })
})
