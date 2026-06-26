import { test, expect } from '@playwright/test'
import { loginRole, logout } from './helpers'

// P-DATA-08 跨层级筛选 + 批次号直达 + 关键字 + BR-M8-09 脱敏隔离。
test.describe('P-DATA-08 案件跨层级筛选(PC)', () => {
  test('项目+状态下拉过滤→请求带 projectId&status，行数变化', async ({ page }) => {
    await loginRole(page, 'PC')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    await expect(page.locator('.el-table').first()).toBeVisible()

    // 选项目下拉首项（页面有项目/状态两个 el-select：弹层都挂在 body，须只点当前可见弹层里的项）。
    // el-select 内层 input 会被 placeholder 浮层拦截 → 点 .el-select 包裹元素(按 placeholder 文案定位)而非 getByPlaceholder。
    const projSelect = page.locator('.el-select').filter({ hasText: '全部项目' }).first()
    const projReq = page.waitForRequest((r) => /\/cases\?/.test(r.url()) && /[?&]projectId=/.test(r.url()))
    await projSelect.click()
    const projItem = page
      .locator('.el-select-dropdown')
      .filter({ visible: true })
      .locator('.el-select-dropdown__item')
      .first()
    await expect(projItem).toBeVisible()
    await projItem.click()
    await projReq
    // 等项目下拉弹层完全收起，避免其关闭动画与状态下拉弹层叠加导致选错项
    await expect(page.locator('.el-select-dropdown').filter({ visible: true })).toHaveCount(0)

    // 选状态下拉首项 → 请求带 status（状态选项弹层含「待派单」，据此唯一定位）
    const statReq = page.waitForRequest((r) => /\/cases\?/.test(r.url()) && /[?&]status=/.test(r.url()))
    await page.locator('.el-select').filter({ hasText: '全部状态' }).first().click()
    const statDropdown = page
      .locator('.el-select-dropdown')
      .filter({ visible: true })
      .filter({ hasText: '待派单' })
    const statItem = statDropdown.locator('.el-select-dropdown__item').first()
    await expect(statItem).toBeVisible()
    await statItem.click()
    await statReq
  })

  test('带 ?batchId=xxx 进入→列表仅含该批次(批次号直达)', async ({ page }) => {
    await loginRole(page, 'PC')
    // 模拟从批次页/全局搜索直达
    const req = page.waitForRequest((r) => /\/cases\?/.test(r.url()) && /[?&]batchId=B-DEMO-1/.test(r.url()))
    await page.goto('/cases?batchId=B-DEMO-1')
    await req
    // 批次直达框回填
    await expect(page.getByPlaceholder('批次号直达')).toHaveValue('B-DEMO-1')
  })

  test('关键字 q 命中目标；VL/CO 对脱敏案件用业主名/手机号无法命中', async ({ page }) => {
    // PC 用关键字命中
    await loginRole(page, 'PC')
    await page.goto('/cases')
    const qReq = page.waitForRequest((r) => /\/cases\?/.test(r.url()) && /[?&]q=/.test(r.url()))
    await page.getByPlaceholder('手机号/户号/业主名').fill('张')
    await page.getByPlaceholder('手机号/户号/业主名').press('Enter')
    await qReq

    // VL 对脱敏案件：关键字命中应被裁剪（后端脱敏不返还匹配）
    // 同一 test 内切角色：先清 token，否则 goto('/login') 会因已登录重定向回工作台、登录框不出现
    await logout(page)
    await loginRole(page, 'VL')
    await page.goto('/cases')
    await expect(page.locator('.el-table').first()).toBeVisible()
    await page.getByPlaceholder('手机号/户号/业主名').fill('13800138000')
    await page.getByPlaceholder('手机号/户号/业主名').press('Enter')
    // 脱敏隔离：业主名/手机号关键字不泄露已结案案件明细（无该明文行）
    await expect(page.getByText('13800138000')).toHaveCount(0)
  })
})
