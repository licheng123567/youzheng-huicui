import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M4-12 / BR-M4-22 通话记录查询：菜单「通话记录」→ /call-records 列表(GET /recordings)；
// phone 过滤请求带 phone 参数；点行进 CallRecordView(转写/AI 复盘)。
test.describe('US-M4-12 通话记录查询(SA)', () => {
  test('点「通话记录」菜单→/call-records 列表渲染；phone 过滤带参', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '通话记录' }).click()
    await expect(page).toHaveURL(/\/call-records/)
    await expect(page.locator('.el-table').first()).toBeVisible()

    // 输入 phone 过滤 → 断言后端请求带 phone 参数
    const reqP = page.waitForRequest((r) => /\/recordings(\?|$)/.test(r.url()) && /[?&]phone=/.test(r.url()))
    await page.getByPlaceholder('号码').fill('139')
    await page.getByPlaceholder('号码').press('Enter')
    await reqP
  })

  test('列表点行「AI复盘/详情」→/cases/:id/call/:callId 渲染转写/AI 复盘卡', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/call-records')
    await expect(page.locator('.el-table').first()).toBeVisible()
    const rows = page.locator('.el-table__row')
    if (!(await rows.count())) {
      test.skip(true, '无通话记录数据')
    }
    await page.getByRole('button', { name: 'AI 复盘/详情' }).first().click()
    await expect(page).toHaveURL(/\/cases\/\d+\/call\/\d+/)
    await expect(page.getByText('转写文本')).toBeVisible()
  })
})
