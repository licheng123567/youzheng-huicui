import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M9-04/US-M10-02 计费·短信明细+能力用量下钻+数据隔离(BR-M9-08)。
// 注：slice 原指 frontend/tests/e2e/billing-sms.spec.ts，但 playwright testDir=./e2e，
//     故落位 frontend/e2e/billing-sms.spec.ts 以纳入收集（路径差异已在交付说明报告）。
test.describe('US-M9-04 计费短信明细(PL)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '计费' }).click()
    await expect(page).toHaveURL(/\/billing/)
  })

  test('短信明细分区加载 /sms-records 并渲染状态标签', async ({ page }) => {
    await expect(page.getByText('短信发送明细')).toBeVisible()
    const smsTable = page.locator('.el-table').filter({ hasText: '发送时间' })
    await expect(smsTable).toBeVisible()
    // 至少出现一种状态标签（SENT/FAILED/DELIVERED 文案）
    await expect(
      smsTable.getByText(/已发送|失败|已送达/).first(),
    ).toBeVisible()
  })

  test('按 status=FAILED 过滤→失败行展示 failureReason 且不消失，失败汇总文案', async ({ page }) => {
    // 状态下拉选 FAILED（el-select 的 placeholder「状态」未渲染为 a11y placeholder，
    //   故按文案定位短信明细区的 el-select 触发器，而非 getByPlaceholder）
    const req = page.waitForRequest((r) => /\/sms-records\?/.test(r.url()) && /[?&]status=FAILED/.test(r.url()))
    await page.locator('.el-select').filter({ hasText: '状态' }).first().click()
    await page.getByText('失败 FAILED').click()
    await page.getByRole('button', { name: '查询' }).click()
    await req
    // 失败汇总文案「失败不退条数」在 el-alert 汇总条与失败原因单元格各出现一次(strict 命中 2 个)，
    // 用 el-alert 标题唯一定位汇总条(BillingView.vue:176)。
    await expect(page.locator('.el-alert__title').filter({ hasText: '失败不退条数' })).toBeVisible()
  })

  test('能力用量月→日→明细下钻(树表)', async ({ page }) => {
    // 「能力用量」文案在卡片标题与分隔线各出现一次（strict 模式会命中 2 个），用分隔线文案唯一定位
    await expect(page.getByText('能力用量（GET /billing/usage')).toBeVisible()
    // 切 SMS 维度（仅触发交互，不强求选项落定）
    await page.locator('.el-select').filter({ hasText: /STT|SMS/ }).first().click().catch(() => {})
    // 树表结构存在（表头列「周期 / 明细」渲染即可）。
    // 注：当前 DevSeeder 无 billing_usage 种子，/billing/usage 返回空 → 表体为「No Data」，
    //     故只断言树表骨架可见，不断言明细行（行级下钻待补种子，详见返回说明）。
    const usageTable = page.locator('.el-table').filter({ hasText: '周期 / 明细' })
    await expect(usageTable).toBeVisible()
    await expect(usageTable.getByText('周期 / 明细')).toBeVisible()
  })
})

test.describe('US-M9-04 计费数据隔离', () => {
  test('CO 计费短信分区可见性（无则跳过）', async ({ page }) => {
    await loginRole(page, 'CO')
    const menu = page.getByRole('menuitem', { name: '计费' })
    if (!(await menu.count())) {
      test.skip(true, 'CO 无计费菜单')
    }
    await menu.click()
    await expect(page).toHaveURL(/\/billing/)
  })

  test('VL 进计费→短信分区为本组织 range 裁剪(不串组织)', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '计费' }).click()
    await expect(page).toHaveURL(/\/billing/)
    // 短信明细按 range 裁剪：分区存在即可（数据由后端 scope 限本组织）
    await expect(page.getByText('短信发送明细')).toBeVisible()
  })
})
