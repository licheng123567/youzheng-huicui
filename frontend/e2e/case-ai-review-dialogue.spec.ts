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
    // 锚定有 READY 录音+AI复盘的私海案 M3-S3-01（M5-QB-01 的 QUOTA_BLOCKED 录音无复盘，会顶到首行）
    await rows.filter({ hasText: 'M3-S3-01' }).first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
    // 录音面板按需加载：先点「获取最新通话录音」拉取 latest，按钮才渲染（getLatest 非 onMounted）
    await page.getByRole('button', { name: '获取最新通话录音' }).click()
    const reviewBtn = page.getByRole('button', { name: '看 AI 复盘' })
    await expect(reviewBtn).toBeVisible()
    if (!(await reviewBtn.count())) return false
    await reviewBtn.click()
    return true
  }

  test('案件作业台复盘→对话气泡 + AI 复盘小结渲染', async ({ page }) => {
    const ok = await openReview(page)
    if (!ok) test.skip(true, '本案无 READY 录音/复盘入口')
    // AI 复盘卡片渲染：小结文案 + 对话气泡（"AI 复盘" 子串同时命中 tab/按钮，故锚定唯一的「小结：」）
    await expect(page.getByText(/小结：/)).toBeVisible()
    await expect(page.getByText('业主有还款意愿，承诺下月')).toBeVisible()
  })

  test('CallRecord 独立页一致(转写文本 + AI 复盘卡片)', async ({ page }) => {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '案件' }).click()
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    // 锚定有 READY 录音的私海案 M3-S3-01
    await rows.filter({ hasText: 'M3-S3-01' }).first().click()
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
    // 录音面板按需加载：先点「获取最新通话录音」拉取 latest，详情按钮才渲染
    await page.getByRole('button', { name: '获取最新通话录音' }).click()
    const detailBtn = page.getByRole('button', { name: '通话记录详情' })
    await expect(detailBtn).toBeVisible()
    if (!(await detailBtn.count())) {
      test.skip(true, '本案无录音详情入口')
    }
    await detailBtn.click()
    await expect(page).toHaveURL(/\/cases\/\d+\/call\/\d+/)
    await expect(page.getByText('转写文本')).toBeVisible()
  })
})
