import { test, expect } from '@playwright/test'
import { loginRole, openBatchDetail } from './helpers'

// BR-M2-18b / BR-M5-05a/b 批次级作战手册覆盖同步（issue #2 批次独立覆盖手册）：
// source=INHERITED→采纳批次自定义内容→source=CUSTOM；恢复继承(content=null)→source=INHERITED。
// 操作 B-CH-2026-01（演示批次，非首批次）以隔离 batch-sync-drift.spec 的首批次手册 drift 种子。
//
// UI 现状（ds-admin 改版后）：来源用两个 radio「继承项目 / 自定义覆盖」切换，不再是「采纳/编辑」「恢复继承项目」按钮。
//   选「自定义覆盖」→ 开 DsDrawer「采纳批次作战手册」→ 填内容 →「采纳发布」。
//   选「继承项目」  → ElMessageBox 确认 → 清除批次覆盖。
// PL 无「撮合派单」菜单，经「案件管理」批次优先入口下钻（openBatchDetail 按角色选入口）。

const PB_CARD = '作战手册（批次级）'

function playbookCard(page: any) {
  return page.locator('.card').filter({ hasText: PB_CARD })
}
const inheritRadio = (page: any) => playbookCard(page).getByText('继承项目', { exact: true })
const customRadio = (page: any) => playbookCard(page).getByText('自定义覆盖', { exact: true })

async function openDemoBatch(page: any) {
  // loadPlaybook 是异步的：必须等它回来再读 source 态，否则 .pb-content 计数会读到 0，
  // 把 CUSTOM 误判成 INHERITED → 去点「自定义覆盖」radio，而它本就已选中 → @change 不触发 → 抽屉不开。
  const pb = page.waitForResponse((r: any) => /\/batches\/\d+\/playbook/.test(r.url())).catch(() => {})
  await openBatchDetail(page, 'PL', 'B-CH-2026-01', 'props')
  await pb
  await expect(playbookCard(page)).toBeVisible()
  // 等来源分支渲染稳定：要么显示自定义正文块，要么显示继承说明
  await expect(
    playbookCard(page).locator('.pb-content')
      .or(playbookCard(page).getByText('继承项目级作战手册（项目修改后自动跟随）。'))
      .first(),
  ).toBeVisible()
}

/** 若当前是 CUSTOM，先恢复继承，保证用例从 INHERITED 起步（用例可重复跑）。 */
async function ensureInherited(page: any) {
  const content = playbookCard(page).locator('.pb-content')
  if (await content.count()) {
    await inheritRadio(page).click()
    await page.locator('.el-message-box').getByRole('button', { name: /OK|确定|确认/ }).click()
    await expect(page.getByText('已恢复继承项目手册')).toBeVisible()
  }
  await expect(playbookCard(page).getByText('继承项目级作战手册（项目修改后自动跟随）。')).toBeVisible()
}

test.describe('BR-M2-18b 批次作战手册(PL)', () => {
  test('采纳批次自定义内容→source 变批次自定义', async ({ page }) => {
    await loginRole(page, 'PL')
    await openDemoBatch(page)
    await ensureInherited(page)

    await customRadio(page).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '采纳批次作战手册' })
    await expect(dlg).toBeVisible()
    await dlg.locator('textarea').first().fill('E2E 批次自定义手册：先共情后引导分期。')
    await dlg.getByRole('button', { name: '采纳发布' }).click()
    await expect(page.getByText('已采纳为批次自定义手册')).toBeVisible()

    // V915 起批次有独立存储：采纳后 GET /batches/{id}/playbook 的 source=CUSTOM →
    // 手册区渲染自定义正文块(.pb-content)，且不再显示「继承项目级」说明。
    await expect(playbookCard(page).locator('.pb-content')).toContainText('E2E 批次自定义手册：先共情后引导分期。')
    await expect(playbookCard(page).getByText('继承项目级作战手册（项目修改后自动跟随）。')).toHaveCount(0)
  })

  test('恢复继承(content=null)→source 回继承项目', async ({ page }) => {
    await loginRole(page, 'PL')
    await openDemoBatch(page)

    // 构造 CUSTOM 前置态（本用例可独立运行，不依赖上一用例）。
    if (!(await playbookCard(page).locator('.pb-content').count())) {
      await customRadio(page).click()
      const dlg = page.getByRole('dialog').filter({ hasText: '采纳批次作战手册' })
      await expect(dlg).toBeVisible()
      await dlg.locator('textarea').first().fill('E2E 临时批次手册（待恢复）。')
      await dlg.getByRole('button', { name: '采纳发布' }).click()
      await expect(page.getByText('已采纳为批次自定义手册')).toBeVisible()
    }
    await expect(playbookCard(page).locator('.pb-content')).toBeVisible()

    await inheritRadio(page).click()
    // ElMessageBox 确认键：未配中文 locale 时渲染 OK/Cancel。
    await page.locator('.el-message-box').getByRole('button', { name: /OK|确定|确认/ }).click()
    await expect(page.getByText('已恢复继承项目手册')).toBeVisible()
    // 删批次级覆盖行 → source 回 INHERITED → 自定义正文块消失，回到继承说明。
    await expect(playbookCard(page).locator('.pb-content')).toHaveCount(0)
    await expect(playbookCard(page).getByText('继承项目级作战手册（项目修改后自动跟随）。')).toBeVisible()
  })
})
