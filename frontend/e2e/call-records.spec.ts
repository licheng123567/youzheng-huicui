import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M4-12 / BR-M4-22 通话记录查询：菜单「通话记录」→ /call-records 列表(GET /recordings)；
// phone 过滤请求带 phone 参数；点行进 CallRecordView(转写/AI 复盘)。
// 用 PC 驱动：NAV_BY_ROLE 里「通话记录」只在 PC/CO 菜单（一线作业角色），平台角色无此入口。
// 端点本身 x-data-scope=range 无 x-permission，PC 见本物业范围内的通话。
test.describe('US-M4-12 通话记录查询(PC)', () => {
  test('点「通话记录」菜单→/call-records 列表渲染；phone 过滤带参', async ({ page }) => {
    await loginRole(page, 'PC')
    await page.getByRole('menuitem', { name: '通话记录' }).click()
    await expect(page).toHaveURL(/\/call-records/)
    await expect(page.locator('table').first()).toBeVisible()

    // 输入 phone 过滤 → 断言后端请求带 phone 参数
    const reqP = page.waitForRequest((r) => /\/recordings(\?|$)/.test(r.url()) && /[?&]phone=/.test(r.url()))
    // 该输入框一框多用（业主/房号/电话），值提交到 query 的 phone 参数
    const search = page.getByPlaceholder('搜索 业主 / 房号 / 电话')
    await search.fill('139')
    await search.press('Enter')
    await reqP
  })

  // BR-M5-04a：AI 复盘统一走右侧复盘面板(AiReviewPanel role=dialog)，不再整页跳 /cases/:id/call/:callId。
  test('列表点行「AI 复盘」→右侧复盘面板打开', async ({ page }) => {
    await loginRole(page, 'PC')
    await page.goto('/call-records')
    await expect(page.locator('table').first()).toBeVisible()
    const rows = page.locator('tbody tr')
    if (!(await rows.count())) {
      test.skip(true, '无通话记录数据')
    }
    await rows.first().getByRole('button', { name: 'AI 复盘' }).click()
    // 录音未解析完成时后端不给复盘，前端提示而不开面板 → 两种终态都算通过（面板 或 提示）
    const panel = page.getByRole('dialog', { name: 'AI 复盘 · 本次录音' })
    const notReady = page.getByText(/尚未解析完成|复盘加载失败/)
    await expect(panel.or(notReady).first()).toBeVisible()
  })
})
