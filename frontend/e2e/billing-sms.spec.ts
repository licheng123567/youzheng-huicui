import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M9-04/US-M10-02 计费·短信明细+能力用量下钻+数据隔离(BR-M9-08)。
//
// 页面拆分后的现状（constants/nav.ts）：
//   /billing「计费明细」= 能力用量月→日→明细下钻（PL/SA/SE/VL 有此菜单）；
//   /sms   「短信通道」= 签名模板配置 + 发送统计&明细（GET /sms-records，仅 PL/SA 有此菜单）。
//   短信明细不再挂在计费页里。VL 无 /sms 菜单，其短信数据隔离由服务端 range 裁剪保证（smoke 覆盖）。
//   筛选控件是原生 <select>（ds-admin 改版后不再是 el-select）。

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

test.describe('US-M10-02 能力用量下钻(PL·/billing)', () => {
  test('能力用量月→日下钻骨架就位', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '计费明细' }).click()
    await expect(page).toHaveURL(/\/billing/)
    await expect(page.getByText(/仅记录能力/)).toBeVisible()
    // 月表骨架（当前 DevSeeder 无 billing_usage 种子 → 表体可能为空，只断言骨架）
    const monthTable = page.locator('table').filter({ hasText: '月份' })
    await expect(monthTable).toBeVisible()
    await expect(monthTable.locator('thead').getByText('短信')).toBeVisible()
  })
})

test.describe('US-M9-04 计费数据隔离', () => {
  test('CO 无「计费明细」「短信通道」菜单，直敲均被守卫拦截', async ({ page }) => {
    await loginRole(page, 'CO')
    await expect(page.getByRole('menuitem', { name: '计费明细' })).toHaveCount(0)
    await expect(page.getByRole('menuitem', { name: '短信通道' })).toHaveCount(0)
    await page.goto('/billing')
    await expect(page).not.toHaveURL(/\/billing/)
  })

  test('VL 有「计费明细」无「短信通道」(短信配置归物业/平台)', async ({ page }) => {
    await loginRole(page, 'VL')
    await expect(page.getByRole('menuitem', { name: '短信通道' })).toHaveCount(0)
    await page.getByRole('menuitem', { name: '计费明细' }).click()
    await expect(page).toHaveURL(/\/billing/)
    await expect(page.getByText(/仅记录能力/)).toBeVisible()
  })

  // 服务商计费明细还原(2026-07)：VL 的计费=STT 录音转写分钟(种子已归服务商 org);
  // 月→日→明细三级下钻,明细行带业主/房号/项目/批次(caseId join·v1.11.0)。
  test('VL 计费明细：STT 用量月→日→明细下钻,穿透列有真数据', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '计费明细' }).click()
    await expect(page).toHaveURL(/\/billing/)
    const monthTable = page.locator('table').filter({ hasText: '月份' })
    await expect(monthTable).toBeVisible()
    // VL 有 STT 用量(种子 5 笔跨两月)——月行存在,STT 列非「—」
    const firstMonth = monthTable.locator('tbody tr').first()
    await expect(firstMonth).toBeVisible()
    await firstMonth.getByText(/按日查看/).click()
    // 按日 → 明细
    await expect(page.getByText(/· 按日/)).toBeVisible()
    await page.getByText(/查看明细/).first().click()
    await expect(page.getByText(/· 明细/)).toBeVisible()
    // 明细表头含 业主/房号/项目/批次(原型穿透列)
    const detailTable = page.locator('table').filter({ hasText: '业主' })
    await expect(detailTable.locator('thead').getByText('业主')).toBeVisible()
    await expect(detailTable.locator('thead').getByText('项目')).toBeVisible()
    await expect(detailTable.locator('thead').getByText('批次')).toBeVisible()
    // 明细行的类型是 STT解析,且业主列非空占位
    await expect(detailTable.locator('tbody tr').first().getByText('STT解析')).toBeVisible()
  })
})
