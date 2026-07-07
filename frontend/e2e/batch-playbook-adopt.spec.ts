import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M2-18b / BR-M5-05a/b 批次级作战手册覆盖同步（issue #2 批次独立覆盖手册）：
// source=INHERITED→采纳批次自定义内容→source=CUSTOM；恢复继承(content=null)→source=INHERITED。
// 操作 B-CH-2026-01（演示批次，非首批次）以隔离 batch-sync-drift.spec 的首批次手册 drift 种子。
async function openDemoBatch(page: any) {
  await page.getByRole('menuitem', { name: '批次' }).click()
  await expect(page).toHaveURL(/\/batches/)
  // 批次号列是 link 按钮(@click=$router.push(/batches/:id))，点 B-CH-2026-01 那行进详情。
  const link = page.getByRole('button', { name: 'B-CH-2026-01' })
  await expect(link.first()).toBeVisible()
  await link.first().click()
  await expect(page).toHaveURL(/\/batches\/\d+/)
  // 等批次作战手册区渲染完（异步 loadPlaybook 后按钮才挂载），避免过早 count() 误判跳过。
  await expect(page.getByText('批次作战手册')).toBeVisible()
}

test.describe('BR-M2-18b 批次作战手册(PL)', () => {
  test('采纳批次自定义内容→source 变批次自定义', async ({ page }) => {
    await loginRole(page, 'PL')
    await openDemoBatch(page)
    const btn = page.getByRole('button', { name: '采纳/编辑' })
    // 等权限/手册区就绪（auth.has 异步），按钮在则可见；PL 恒有 playbook.adopt。
    await expect(btn).toBeVisible()
    await btn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '作战手册' })
    await expect(dlg).toBeVisible()
    await dlg.locator('textarea').first().fill('E2E 批次自定义手册：先共情后引导分期。')
    await dlg.getByRole('button', { name: '采纳发布' }).click()
    await expect(page.getByText('已采纳为批次自定义手册')).toBeVisible()
    // V915 起批次有独立存储：采纳后 GET /batches/{id}/playbook 的 source=CUSTOM →
    // 手册区来源标签显示「批次自定义」，且自定义正文在批次详情可见。
    await expect(page.getByText('批次自定义').first()).toBeVisible()
    await expect(page.getByText('E2E 批次自定义手册：先共情后引导分期。')).toBeVisible()
  })

  test('恢复继承(content=null)→source 回继承项目', async ({ page }) => {
    await loginRole(page, 'PL')
    await openDemoBatch(page)
    // 上一用例已把本批次置 CUSTOM；「恢复继承项目」按钮在 playbookSource==='CUSTOM' 时渲染
    // (BatchDetailView)。若未渲染（独立运行此用例时无前置 CUSTOM 态）则先采纳一次造 CUSTOM。
    let restoreBtn = page.getByRole('button', { name: '恢复继承项目' })
    if (!(await restoreBtn.count())) {
      const adopt = page.getByRole('button', { name: '采纳/编辑' })
      if (!(await adopt.count())) {
        test.skip(true, 'PL 无 playbook.adopt，无法构造 CUSTOM 前置态')
      }
      await adopt.click()
      const dlg = page.locator('.el-dialog').filter({ hasText: '作战手册' })
      await expect(dlg).toBeVisible()
      await dlg.locator('textarea').first().fill('E2E 临时批次手册（待恢复）。')
      await dlg.getByRole('button', { name: '采纳发布' }).click()
      await expect(page.getByText('已采纳为批次自定义手册')).toBeVisible()
      restoreBtn = page.getByRole('button', { name: '恢复继承项目' })
    }
    await expect(restoreBtn).toBeVisible()
    await restoreBtn.click()
    // ElMessageBox 确认键：未配中文 locale，默认渲染 OK/Cancel（英文），确认=OK。
    await page.locator('.el-message-box').getByRole('button', { name: /OK|确定|确认/ }).click()
    await expect(page.getByText('已恢复继承项目手册')).toBeVisible()
    // 删批次级覆盖行 → source 回 INHERITED → 标签显示「继承项目默认」。
    await expect(page.getByText('继承项目默认').first()).toBeVisible()
  })
})
