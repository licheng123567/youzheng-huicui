import { test, expect } from './fixtures/test'
import { loginRole, loginAs } from './helpers'

// 质检/风控处置逻辑修正(BR-M5-07a/b/c):平台去上报只复核处置、反馈组织负责人;物业侧"谁的员工谁处置"。
test.describe('平台质检:无上报,只复核处置', () => {
  test('SA 风险看板无「上报」按钮,有「复核处置」;违规员工带角色/组织', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '质检/风控' }).click()
    await expect(page).toHaveURL(/\/risks/)
    const rows = page.locator('tbody tr')
    await expect(rows.first()).toBeVisible()
    // 平台不该有「上报」按钮
    await expect(page.getByRole('button', { name: '上报' })).toHaveCount(0)
    // 平台有「复核处置」
    await expect(page.getByRole('button', { name: '复核处置' }).first()).toBeVisible()
    // 违规员工列带角色标签(催收员/协调员)
    await expect(page.locator('tbody').getByText(/催收员|协调员/).first()).toBeVisible()
  })

  test('SA 复核处置弹窗提示反馈对象(服务商/物业负责人)', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '质检/风控' }).click()
    await page.getByRole('button', { name: '复核处置' }).first().click()
    const dlg = page.getByRole('dialog').filter({ hasText: '平台复核处置' })
    await expect(dlg).toBeVisible()
    // 确认属实默认→提示反馈给组织负责人执行整改
    await expect(dlg.getByText(/反馈给/)).toBeVisible()
    await expect(dlg.getByText(/负责人/)).toBeVisible()
  })
})

test.describe('物业侧质检:谁的员工谁处置', () => {
  test('PL 对本物业协调员违规看到「处置」(ownScope)', async ({ page }) => {
    await loginAs(page, 'cuihu_pl')
    await page.getByRole('menuitem', { name: '质检/风控' }).click()
    await expect(page).toHaveURL(/\/risks/)
    const rows = page.locator('tbody tr')
    await expect(rows.first()).toBeVisible()
    // 协调员(PC)违规行应有「处置」按钮(本物业员工);催收员违规行应是「上报」
    // 至少存在一个处置入口(本物业 PC 违规种子)
    await expect(page.getByRole('button', { name: '处置' }).first()).toBeVisible()
  })
})
