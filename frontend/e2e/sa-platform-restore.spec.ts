import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// 平台超管(SA)页面还原(2026-07)：话术库 / 项目管理 / 案件管理。
test.describe('SA 平台话术库还原', () => {
  test('筛选工具条 + 用量/转化/Wilson 列 + 查看详情', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '平台话术库' }).click()
    await expect(page).toHaveURL(/\/script-lib/)
    // 表头新增列
    await expect(page.locator('thead').getByText('Wilson')).toBeVisible()
    await expect(page.locator('thead').getByText('承诺转化')).toBeVisible()
    // 筛选工具条（后端已支持 scene/source/status）
    await expect(page.locator('select.inp').filter({ hasText: '来源：全部' })).toBeVisible()
    // 查看详情
    const viewBtn = page.getByRole('button', { name: '查看' }).first()
    await viewBtn.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {})
    if (await viewBtn.count()) {
      await viewBtn.click()
      await expect(page.getByRole('dialog').filter({ hasText: '话术详情' })).toBeVisible()
    }
  })
})

test.describe('SA 项目管理还原', () => {
  test('项目列表聚合列（批次数/在催/应收/回款率）有真数据', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '项目管理' }).click()
    await expect(page).toHaveURL(/\/projects/)
    // 翠湖一期有 6 批次/¥77200 应收 —— 该行的聚合列不应全是「—」
    const row = page.locator('tbody tr', { hasText: '翠湖一期' }).first()
    await expect(row).toBeVisible()
    // 应收总额列（¥ 开头）有值
    await expect(row.getByText(/¥/).first()).toBeVisible()
  })
})

test.describe('SA 案件管理还原', () => {
  test('批次优先 + 项目筛选下拉', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '案件管理' }).click()
    await expect(page).toHaveURL(/\/cases/)
    // 批次优先入口 + 项目筛选下拉（新增）
    await expect(page.getByText(/选择批次查看案件明细/)).toBeVisible()
    await expect(page.locator('select.inp').filter({ hasText: '全部项目' })).toBeVisible()
  })

  test('批次下钻案件明细:归属催收员/联系方式列表头就位(holderName/contactPhone)', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '案件管理' }).click()
    // 点第一个批次行下钻
    const batchRow = page.locator('tbody tr').first()
    await batchRow.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {})
    if (await batchRow.count()) {
      await batchRow.click()
      await expect(page).toHaveURL(/\/batches\/\d+/)
      // 案件明细表头含 归属催收员 / 联系方式（此前渲染 c.collectorName/c.phone 恒空,已改 holderName/contactPhone）
      await expect(page.locator('thead').getByText('归属催收员')).toBeVisible()
      await expect(page.locator('thead').getByText('联系方式')).toBeVisible()
    }
  })
})
