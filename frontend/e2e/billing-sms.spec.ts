import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M9-04 短信明细（BR-M9-08 失败不退条数）。
//
// 页面现状（constants/nav.ts）：
//   /sms「短信通道」= 签名模板配置 + 发送统计&明细（GET /sms-records，仅 PL/SA 有此菜单）。
//   计费/额度相关断言已随 v1.19.0「额度管理」合并页迁至 quota.spec.ts（/billing、/recharge 两页已删）。

test.describe('US-M9-04 短信明细(PL·/sms)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '短信通道' }).click()
    await expect(page).toHaveURL(/\/sms/)
  })

  test('发送统计&明细分区加载 /sms-records 并渲染明细表', async ({ page }) => {
    await expect(page.getByText('发送统计 & 明细')).toBeVisible()
    const smsTable = page.locator('table').filter({ hasText: '失败原因' })
    await expect(smsTable).toBeVisible()
    await expect(smsTable.locator('thead').getByText('状态')).toBeVisible()
  })

  test('按 status=FAILED 过滤→请求带 status，失败原因列在位', async ({ page }) => {
    const req = page.waitForRequest((r) => /\/sms-records\?/.test(r.url()) && /[?&]status=FAILED/.test(r.url()))
    // 状态筛选是原生 <select>，change 即触发 load()
    await page.locator('select.inp').filter({ hasText: '状态：全部' }).selectOption('FAILED')
    await req
    await expect(page.locator('table').filter({ hasText: '失败原因' })).toBeVisible()
    // 计费口径提示：失败不退条数但记原因
    await expect(page.locator('.note').filter({ hasText: '失败不退条数' })).toBeVisible()
  })
})
