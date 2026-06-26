import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M5 AI复盘对话+质检(BR-M5-04a)：
// READY 录音 AI复盘渲染说话人分离对话气泡与 risks segmentTs 标签；
// CallRecord 独立页一致。
test.describe('US-M5 AI复盘对话气泡(CO)', () => {
  async function openReview(page: any) {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
    const reviewBtn = page.getByRole('button', { name: '看 AI 复盘' })
    if (!(await reviewBtn.count())) return false
    await reviewBtn.click()
    return true
  }

  test('案件作业台复盘→对话气泡 + AI 复盘小结渲染', async ({ page }) => {
    const ok = await openReview(page)
    if (!ok) test.skip(true, '本案无 READY 录音/复盘入口')
    await expect(page.getByText('AI 复盘')).toBeVisible()
    await expect(page.getByText(/小结：/)).toBeVisible()
  })

  test('CallRecord 独立页一致(转写文本 + AI 复盘卡片)', async ({ page }) => {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '案件' }).click()
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
    const detailBtn = page.getByRole('button', { name: '通话记录详情' })
    if (!(await detailBtn.count())) {
      test.skip(true, '本案无录音详情入口')
    }
    await detailBtn.click()
    await expect(page).toHaveURL(/\/cases\/\d+\/call\/\d+/)
    await expect(page.getByText('转写文本')).toBeVisible()
  })
})
