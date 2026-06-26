import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// v1.1.0 契约扩展 P0：角色工作台(BR-M4-20) + 派单决策辅助(BR-M3-24) 真屏。
test.describe('v1.1.0 工作台 + 派单决策', () => {
  test('CO 工作台=今日驾驶舱(今日必办区就位)', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await expect(page).toHaveURL(/\/dashboard/)
    await expect(page.getByText('今日必办')).toBeVisible()             // cockpit 形态
    await expect(page.getByText(/工作台 今日驾驶舱/)).toBeVisible()
  })

  test('SA 工作台=仪表盘形态', async ({ page }) => {
    await loginAs(page, 'admin')
    await expect(page.getByText(/工作台 仪表盘/)).toBeVisible()
    await expect(page.getByText('今日必办')).toHaveCount(0)           // 管理角色无驾驶舱
  })

  test('SA 派单对话框→服务商指标决策辅助就位', async ({ page }) => {
    await loginAs(page, 'admin')
    await page.getByRole('menuitem', { name: '批次' }).click()
    await expect(page).toHaveURL(/\/batches/)
    // 第一批次的派单按钮
    await page.getByRole('button', { name: '派单' }).first().click()
    await page.getByRole('button', { name: /加载各服务商指标/ }).click()
    await expect(page.getByText('近30天回款率')).toBeVisible()        // 指标表头
    await expect(page.getByText('捷信催收')).toBeVisible()
  })
})
