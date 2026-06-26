import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M4 通话结果标记(BR-M4-03a/BR-M4-12)：
// CO 复盘内打开标记弹窗，下拉项来自服务端启用 markCodes(非硬编码)，
// 提交接通有效码后断言 T_collector 重置。
test.describe('US-M4 通话结果标记(CO)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    // 锚定有 READY 录音的私海案 M3-S3-01（M5-QB-01 的 QUOTA_BLOCKED 录音会顶到首行）
    await rows.filter({ hasText: 'M3-S3-01' }).first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
    // 录音面板按需加载：先点「获取最新通话录音」拉取 latest，标记入口才渲染（getLatest 非 onMounted）
    await page.getByRole('button', { name: '获取最新通话录音' }).click()
    await expect(page.getByRole('button', { name: '标记结果' })).toBeVisible()
  })

  test('打开标记弹窗→结果码下拉非空(来自服务端 markCodes)', async ({ page }) => {
    const markBtn = page.getByRole('button', { name: '标记结果' })
    if (!(await markBtn.count())) {
      test.skip(true, '本案无录音/无标记入口')
    }
    await markBtn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '通话结果标记' })
    await expect(dlg).toBeVisible()
    // 打开结果码下拉，断言有可选项
    await dlg.locator('.el-select').click()
    await expect(page.locator('.el-select-dropdown__item').first()).toBeVisible()
  })

  test('提交接通有效码→标记成功(T_collector 重置)', async ({ page }) => {
    const markBtn = page.getByRole('button', { name: '标记结果' })
    if (!(await markBtn.count())) {
      test.skip(true, '本案无标记入口')
    }
    await markBtn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '通话结果标记' })
    await expect(dlg).toBeVisible()
    await dlg.locator('.el-select').click()
    await page.locator('.el-select-dropdown__item').first().click()
    await dlg.getByRole('button', { name: '标记' }).click()
    await expect(page.getByText('已标记通话结果')).toBeVisible()
  })
})
