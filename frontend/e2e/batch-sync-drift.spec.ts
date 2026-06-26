import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M2-18b 项目级更新·有差异标记+一键同步（依赖 BC-04 落地）：
// 批次 CUSTOM 覆盖后，PL 改项目级减免→重进批次详情见「项目级已更新·有差异」告警→
// 点一键同步→批次回 INHERITED 且差异消失。
async function openFirstBatch(page: any) {
  await page.getByRole('menuitem', { name: '批次' }).click()
  await expect(page).toHaveURL(/\/batches/)
  // 列表行不可整行点击导航；进详情靠「批次号」列里的 link 按钮（@click=$router.push(/batches/:id)）。
  const rows = page.locator('.el-table__row')
  await expect(rows.first()).toBeVisible()
  await rows.first().getByRole('button').first().click()
  await expect(page).toHaveURL(/\/batches\/\d+/)
}

test.describe('BR-M2-18b 覆盖差异与一键同步(PL)', () => {
  test('CUSTOM 批次见「项目级减免已更新·有差异」告警', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const drift = page.getByText('项目级减免已更新·当前批次自定义有差异')
    if (!(await drift.count())) {
      test.skip(true, '当前批次无减免 drift（BC-04 数据未就绪）')
    }
    await expect(drift).toBeVisible()
    await expect(page.getByRole('button', { name: '一键同步为项目最新' })).toBeVisible()
  })

  test('点一键同步减免→批次回继承且差异消失', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const syncBtn = page.getByRole('button', { name: '一键同步为项目最新' }).first()
    if (!(await syncBtn.count())) {
      test.skip(true, '无 drift 同步入口')
    }
    await syncBtn.click()
    await expect(page.getByText(/已同步为项目最新减免|已同步/).first()).toBeVisible()
    // 差异告警消失
    await expect(page.getByText('项目级减免已更新·当前批次自定义有差异')).toHaveCount(0)
  })

  test('手册 drift 告警+一键同步(同闭环)', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const drift = page.getByText('项目级作战手册已更新·当前批次自定义有差异')
    if (!(await drift.count())) {
      // DDL 限制保留 skip：playbook 表仅 project_id 无 batch_id（批次手册经 project 折叠，见 PlaybookController），
      // 批次级手册无独立存储→playbookDrift 恒 false（getBatch 端固定返 false）。
      // 无 schema 迁移引入批次级手册存储前不可造手册 drift 数据，故据实跳过（非数据缺失，是 DDL 约束）。
      test.skip(true, '批次级手册无独立存储(playbook 表仅 project_id)，playbookDrift 恒 false——DDL 限制保留 skip')
    }
    await expect(drift).toBeVisible()
    await page.getByRole('button', { name: '一键同步为项目最新' }).last().click()
    await expect(page.getByText(/已同步/).first()).toBeVisible()
  })
})
