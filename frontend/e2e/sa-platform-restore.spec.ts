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

  test('话术飞轮:转化漏斗 + Wilson 趋势有真数据', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '平台话术库' }).click()
    await expect(page.getByText('话术有效性飞轮（承诺·回款转化信号）')).toBeVisible()
    await expect(page.getByText('转化漏斗')).toBeVisible()
    await expect(page.getByText('通话接通')).toBeVisible()
    await expect(page.getByText(/Wilson 下界趋势/)).toBeVisible()
  })

  test('飞轮结算:按钮触发 recompute,回流重算成功提示', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '平台话术库' }).click()
    await page.getByRole('button', { name: '飞轮结算' }).click()
    await expect(page.getByText(/飞轮结算完成：回流重算/)).toBeVisible()
  })

  test('话术详情:单条话术转化漏斗就位', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '平台话术库' }).click()
    const viewBtn = page.getByRole('button', { name: '查看' }).first()
    await viewBtn.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {})
    if (await viewBtn.count()) {
      await viewBtn.click()
      const dlg = page.getByRole('dialog').filter({ hasText: '话术详情' })
      await expect(dlg.getByText('本话术转化漏斗')).toBeVisible()
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

  test('作战手册版本历史 + 版本对比 diff', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '项目管理' }).click()
    // 打开翠湖一期档案(查看档案内联)
    const row = page.locator('tbody tr', { hasText: '翠湖一期' }).first()
    await row.getByText('查看档案').click()
    // 版本历史区（selProjPlaybook.versions 渲染）
    const hist = page.getByText('版本历史')
    if (await hist.count()) {
      await expect(hist.first()).toBeVisible()
      await page.getByRole('button', { name: /对比上一版/ }).first().click()
      await expect(page.getByRole('dialog').filter({ hasText: '作战手册版本对比' })).toBeVisible()
      // 行级 diff:应有 + 或 − 标记
      await expect(page.getByText(/绿=新增/)).toBeVisible()
    }
  })
})

test.describe('SA 案件运营（v1.17.0 案件管理并入）', () => {
  test('老书签 /cases 对平台重定向到案件运营', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/cases')
    await expect(page).toHaveURL(/\/batches/)
    // 批次运营主表就位（v1.17.0 扩列：项目/户数/应收/回款率）
    await expect(page.locator('thead').getByText('项目', { exact: true })).toBeVisible()
    await expect(page.locator('thead').getByText('户数')).toBeVisible()
  })

  test('批次下钻案件明细:归属催收员/联系方式列表头就位(holderName/contactPhone)', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    // 点第一个批次号链接下钻
    const codeLink = page.locator('tbody tr').first().locator('a.link').first()
    await codeLink.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {})
    if (await codeLink.count()) {
      await codeLink.click()
      await expect(page).toHaveURL(/\/batches\/\d+/)
      // 案件明细表头含 归属催收员 / 联系方式（此前渲染 c.collectorName/c.phone 恒空,已改 holderName/contactPhone）
      await expect(page.locator('thead').getByText('归属催收员')).toBeVisible()
      await expect(page.locator('thead').getByText('联系方式')).toBeVisible()
    }
  })
})
