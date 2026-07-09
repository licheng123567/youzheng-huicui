import { test, expect } from '@playwright/test'
import { loginRole, openBatchDetail } from './helpers'

// BR-M2-18b 项目级更新·有差异标记+一键同步（BC-04 落地）：
// 批次 CUSTOM 覆盖后，项目级更新→批次详情见「项目级…已更新，当前批次自定义有差异」告警→
// 点同步→批次回继承且差异消失。减免与手册各一条告警，按所在卡片作用域取各自同步按钮，
// 避免 .first()/.last() 在另一处 drift 缺失时错点对方按钮（spec 间互不耦合）。
//
// UI 现状（ds-admin 改版后）：告警是 .alert.warn（非 el-alert），按钮文案是「同步为项目最新」（无「一键」二字）。
// PL 无「撮合派单」菜单，经「案件管理」批次优先入口下钻首个批次。

const RD_CARD = '减免政策（批次级）'
const PB_CARD = '作战手册（批次级）'
const REDUCE_DRIFT = '项目级减免已更新，当前批次自定义有差异。'
const PLAYBOOK_DRIFT = '项目级作战手册已更新，当前批次自定义有差异。'

const card = (page: any, title: string) => page.locator('.card').filter({ hasText: title })
const driftAlert = (page: any, cardTitle: string) => card(page, cardTitle).locator('.alert.warn')
const syncBtn = (page: any, cardTitle: string) =>
  driftAlert(page, cardTitle).getByRole('button', { name: '同步为项目最新' })

async function openFirstBatch(page: any) {
  await openBatchDetail(page, 'PL', undefined, 'props')
  // 等两区渲染（异步 loadBatch/loadPlaybook/loadReduceTiers 回来后 drift 告警才挂载）。
  await expect(card(page, PB_CARD)).toBeVisible()
  await expect(card(page, RD_CARD)).toBeVisible()
}

test.describe('BR-M2-18b 覆盖差异与一键同步(PL)', () => {
  test('CUSTOM 批次见「项目级减免已更新·有差异」告警', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const drift = driftAlert(page, RD_CARD)
    if (!(await drift.count())) {
      test.skip(true, '当前批次无减免 drift（前序 spec 已清除批次减免覆盖）')
    }
    await expect(drift).toContainText(REDUCE_DRIFT)
    await expect(syncBtn(page, RD_CARD)).toBeVisible()
  })

  test('点同步减免→批次回继承且差异消失', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const btn = syncBtn(page, RD_CARD)
    if (!(await btn.count())) {
      test.skip(true, '无减免 drift 同步入口')
    }
    await btn.click()
    await expect(page.getByText('已同步为项目最新减免')).toBeVisible()
    await expect(driftAlert(page, RD_CARD)).toHaveCount(0)
  })

  test('手册 drift 告警+同步(同闭环)', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    // V915 起 playbook 有 batch_id 维：首批次种了批次级覆盖手册 + 过去基线，项目级手册更晚 → playbookDrift=true。
    const drift = driftAlert(page, PB_CARD)
    if (!(await drift.count())) {
      test.skip(true, '当前批次无手册 drift（前序 spec 已恢复继承）')
    }
    await expect(drift).toContainText(PLAYBOOK_DRIFT)
    await syncBtn(page, PB_CARD).click()
    await expect(page.getByText('已同步为项目最新手册')).toBeVisible()
    // 删批次级手册覆盖行 → 回继承项目最新 → 手册 drift 告警消失。
    await expect(driftAlert(page, PB_CARD)).toHaveCount(0)
  })
})
