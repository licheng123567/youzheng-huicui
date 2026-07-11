import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// P-DATA-08 案件跨层级筛选 + 批次下钻 + 关键字 + BR-M8-09 脱敏隔离。
//
// 现状（CasesView 按角色分两支，src/views/CasesView.vue）：
//   管理角色(SA/SE/PL/PC/VL)=「批次优先」：顶部筛选打 GET /batches（q=批次号/项目名、status）；
//     点批次行 → /batches/{id} → 该页再打 GET /cases?batchId=（批次内案件明细，含「业主/房号」二级筛选）。
//   催收员(CO)=「我的案件」扁平清单：筛选直接打 GET /cases（q、status）。
//   筛选控件是原生 <select>/<input>（ds-admin 改版后不再是 el-select）。

test.describe('P-DATA-08 案件跨层级筛选', () => {
  test('PC 批次列表：关键字与状态筛选 → 请求带 q & status', async ({ page }) => {
    await loginRole(page, 'PC')
    await page.getByRole('menuitem', { name: '案件管理' }).click()
    await expect(page).toHaveURL(/\/cases/)
    await expect(page.locator('table').first()).toBeVisible()

    const qReq = page.waitForRequest((r) => /\/batches\?/.test(r.url()) && /[?&]q=/.test(r.url()))
    const search = page.getByPlaceholder('批次号/项目名')
    await search.fill('B-CH')
    await search.press('Enter')
    await qReq

    const statusReq = page.waitForRequest((r) => /\/batches\?/.test(r.url()) && /[?&]status=/.test(r.url()))
    await page.locator('select.inp').filter({ hasText: '全部状态' }).selectOption('IN_PROGRESS')
    await statusReq
  })

  test('PC 点批次行下钻 → 批次详情按 batchId 拉案件明细', async ({ page }) => {
    await loginRole(page, 'PC')
    await page.goto('/cases')
    const row = page.locator('tbody tr.row-click').first()
    await expect(row).toBeVisible()

    // 批次详情用 GET /cases?batchId={id} 拉本批次案件（契约 listCases 的 batchId 参数）
    const req = page.waitForRequest((r) => /\/cases\?/.test(r.url()) && /[?&]batchId=\d+/.test(r.url()))
    await row.click()
    await expect(page).toHaveURL(/\/batches\/\d+/)
    await req
  })

  test('CO 我的案件：关键字 q 走 /cases；脱敏案件用手机号搜不出明文(BR-M8-09)', async ({ page }) => {
    await loginRole(page, 'CO')
    await page.goto('/cases')
    await expect(page.locator('table').first()).toBeVisible()

    const qReq = page.waitForRequest((r) => /\/cases\?/.test(r.url()) && /[?&]q=/.test(r.url()))
    // exact：顶栏全局搜索框 placeholder 是「搜案件/业主/房号/电话」，子串会同时命中
    const search = page.getByPlaceholder('业主/房号', { exact: true })
    await search.fill('13800138000')
    await search.press('Enter')
    await qReq

    // 脱敏隔离：结案案件的业主手机号不以明文出现在列表里
    await expect(page.getByText('13800138000')).toHaveCount(0)
  })
})
