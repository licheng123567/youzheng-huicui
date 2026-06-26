import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M1-06/BR-M9-11 资金双线·整列脱敏（裁列而非占位）：
// 服务商(VL/CO)不见收佣比例；物业(PL/PC)不见付佣比例；平台(SA)双线均含真实百分比。
test.describe('BR-M9-11 资金双线·服务商不见收佣比例', () => {
  for (const role of ['VL', 'CO'] as const) {
    test(`${role} /projects 列表无「收佣比例」列头`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/projects')
      await expect(page.locator('.el-table').first()).toBeVisible()
      // 列头不渲染（整列裁掉，非占位）
      await expect(page.locator('.el-table__header').getByText('收佣比例')).toHaveCount(0)
    })

    // 种子现实：服务商(VL/CO)不拥有项目 → GET /projects 为空，永远到不了项目详情。
    // 因此「收佣比例」对服务商在 /projects 域内字段级不可达；断言列表空且全页无收佣比例文案。
    test(`${role} /projects 服务商无项目·收佣比例字段级不可达`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/projects')
      await expect(page.locator('.el-table').first()).toBeVisible()
      // 服务商不拥有项目：无数据行
      await expect(page.locator('.el-table__row')).toHaveCount(0)
      // 收佣比例文案在整页不出现（列头与详情皆不可达）
      await expect(page.getByText('收佣比例')).toHaveCount(0)
    })
  }
})

test.describe('BR-M9-11 资金双线·物业不见付佣比例', () => {
  for (const role of ['PL', 'PC'] as const) {
    test(`${role} /batches 列表无「付佣比例」列头`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/batches')
      await expect(page.locator('.el-table').first()).toBeVisible()
      await expect(page.locator('.el-table__header').getByText('付佣比例')).toHaveCount(0)
      // 不出现占位串泄露字段存在性
      await expect(page.getByText('物业视角不可见')).toHaveCount(0)
    })

    test(`${role} /batches 详情无「付佣比例」项`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/batches')
      const rows = page.locator('.el-table__row')
      await expect(rows.first()).toBeVisible()
      // BatchesView 无 row-click：详情入口=批次号 link 按钮（首行 B-CH-...）
      await rows.first().getByRole('button', { name: /^B-CH-/ }).click()
      await expect(page).toHaveURL(/\/batches\/\d+/)
      await expect(page.locator('.el-descriptions').getByText('付佣比例')).toHaveCount(0)
    })
  }
})

test.describe('D5/BR-M9-11 平台双线全含(回归保护)', () => {
  test('SA /batches 详情·收佣+付佣两项均渲染且有百分比', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/batches')
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    // BatchesView 无 row-click：详情入口=批次号 link 按钮（首列首按钮，区别于操作列派单/重派等）
    await rows.first().getByRole('button', { name: /^B-CH-/ }).click()
    await expect(page).toHaveURL(/\/batches\/\d+/)
    const desc = page.locator('.el-descriptions')
    await expect(desc.getByText('收佣比例')).toBeVisible()
    await expect(desc.getByText('付佣比例')).toBeVisible()
    // 真实百分比（含 %）
    await expect(desc.getByText('%').first()).toBeVisible()
  })
})
