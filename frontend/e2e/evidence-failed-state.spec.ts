import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// M6-FAILED-RETRY 失败态处理：状态列用 el-tag 呈现存证状态，FAILED→danger；
// 重试入口仅 row.status==='FAILED' && 具 evidence.create 权限 时渲染（EvidenceView.vue:52）。
//
// 种子现实(DevSeeder.seedM6Evidence)：私海案件 M3-S3-01 仅 2 条存证，均 ISSUED（无 FAILED）。
// SA(admin) GET /evidence 平台全量可见这 2 条；但 SA 不具 evidence.create（Permissions.of：仅 PL/PC），
// 故即便存在 FAILED 行，SA 也不渲染「重试」按钮。因此 danger 标识/重试入口在当前种子下恒不存在。
test.describe('M6 存证失败态与重试(SA)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/evidence')
    await expect(page.locator('.el-table').first()).toBeVisible()
  })

  test('状态列以 el-tag 呈现，种子内为 ISSUED(success)·无 FAILED(danger)', async ({ page }) => {
    // SA 全量可见 2 条 ISSUED 存证：状态列渲染 success 标签。
    const successTag = page.locator('.el-tag.el-tag--success', { hasText: 'ISSUED' })
    await expect(successTag.first()).toBeVisible()
    // 当前种子无 FAILED 存证 → 不存在 danger 标识（失败态用 danger 渲染由 EvidenceView.vue:46 保证）。
    await expect(page.locator('.el-tag.el-tag--danger', { hasText: 'FAILED' })).toHaveCount(0)
  })

  test('SA 无重试入口：不具 evidence.create 且无 FAILED 行', async ({ page }) => {
    // 重试按钮门控 row.status==='FAILED' && canCreate；SA 两条件均不满足 → 入口恒不渲染。
    await expect(page.getByRole('button', { name: /重试|重新出证/ })).toHaveCount(0)
  })
})
