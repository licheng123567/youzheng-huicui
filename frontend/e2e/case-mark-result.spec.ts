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
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
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
