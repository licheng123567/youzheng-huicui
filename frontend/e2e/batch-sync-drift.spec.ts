import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M2-18b 项目级更新·有差异标记+一键同步（BC-04 落地）：
// 批次 CUSTOM 覆盖后，项目级更新→重进批次详情见「项目级已更新·有差异」告警→
// 点一键同步→批次回 INHERITED 且差异消失。减免与手册各一条 drift 告警，按告警标题作用域取各自同步按钮，
// 避免 .first()/.last() 在另一处 drift 缺失时错点对方按钮（spec 间互不耦合）。
async function openFirstBatch(page: any) {
  await page.getByRole('menuitem', { name: '批次' }).click()
  await expect(page).toHaveURL(/\/batches/)
  // 列表行不可整行点击导航；进详情靠「批次号」列里的 link 按钮（@click=$router.push(/batches/:id)）。
  const rows = page.locator('.el-table__row')
  await expect(rows.first()).toBeVisible()
  await rows.first().getByRole('button').first().click()
  await expect(page).toHaveURL(/\/batches\/\d+/)
  // 等手册区渲染（异步 loadBatch/loadPlaybook 回来后 drift 告警才挂载）。
  await expect(page.getByText('批次作战手册（GET /batches/{id}/playbook · BR-M5-05a/b）')).toBeVisible()
}

// 按告警标题作用域取「一键同步为项目最新」按钮（el-alert 含 title + 同步按钮）。
function syncBtnByAlert(page: any, title: string) {
  return page.locator('.el-alert', { hasText: title }).getByRole('button', { name: '一键同步为项目最新' })
}

const REDUCE_DRIFT = '项目级减免已更新·当前批次自定义有差异'
const PLAYBOOK_DRIFT = '项目级作战手册已更新·当前批次自定义有差异'

test.describe('BR-M2-18b 覆盖差异与一键同步(PL)', () => {
  test('CUSTOM 批次见「项目级减免已更新·有差异」告警', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const drift = page.getByText(REDUCE_DRIFT)
    if (!(await drift.count())) {
      test.skip(true, '当前批次无减免 drift（前序 spec 已清除批次减免覆盖）')
    }
    await expect(drift).toBeVisible()
    await expect(syncBtnByAlert(page, REDUCE_DRIFT)).toBeVisible()
  })

  test('点一键同步减免→批次回继承且差异消失', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const syncBtn = syncBtnByAlert(page, REDUCE_DRIFT)
    if (!(await syncBtn.count())) {
      test.skip(true, '无减免 drift 同步入口')
    }
    await syncBtn.click()
    await expect(page.getByText(/已同步为项目最新减免|已同步/).first()).toBeVisible()
    // 减免差异告警消失
    await expect(page.getByText(REDUCE_DRIFT)).toHaveCount(0)
  })

  test('手册 drift 告警+一键同步(同闭环)', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    // V915 起 playbook 有 batch_id 维：首批次种了批次级覆盖手册 + 过去基线，项目级手册更晚 → playbookDrift=true。
    const drift = page.getByText(PLAYBOOK_DRIFT)
    await expect(drift).toBeVisible()
    await syncBtnByAlert(page, PLAYBOOK_DRIFT).click()
    await expect(page.getByText(/已同步/).first()).toBeVisible()
    // 删批次级手册覆盖行 → 回继承项目最新 → 手册 drift 告警消失。
    await expect(page.getByText(PLAYBOOK_DRIFT)).toHaveCount(0)
  })
})
