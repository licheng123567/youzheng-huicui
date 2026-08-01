import { test, expect } from './fixtures/test'
import { loginRole, openBatchDetail } from './helpers'

// US-M2-02 / BR-M2-13 批次级协调员多对多挂载。
// 门控口径（契约=SSOT）：
//   批次级 PUT /batches/{id}/coordinators → x-permission: batch.import → 仅 SA/SE。
//   项目级 PUT /projects/{id}/coordinators → x-permission: proj.edit  → PL 在「项目档案」维护。
// 故批次详情的「+ 添加协调员」只对平台可见；PL/VL 不可见；PC 自身即协调员；CO 连批次详情都不可达。
// 抽屉是 DsDrawer(role=dialog)，内含 el-transfer + 「保存协调员」。

test.describe('BR-M2-13 批次协调员', () => {
  // 种子无 SE 账号，正向用例由 SA 驱动（SE 与 SA 同持 batch.import）。
  test('SA 维护批次协调员→选 PC 保存→提示已更新', async ({ page }) => {
    await loginRole(page, 'SA')
    await openBatchDetail(page, 'SA', undefined, 'props')
    const btn = page.getByRole('button', { name: '+ 添加协调员' })
    await expect(btn).toBeVisible()
    await btn.click()

    const dlg = page.getByRole('dialog').filter({ hasText: '维护批次协调员' })
    await expect(dlg).toBeVisible()
    // 候选异步加载(GET /members?role=PC)；等 transfer 任一面板出现条目再操作。
    // 全量覆盖语义：重复执行后 PC 可能已全在右侧「已关联」→ 左侧候选为空，此时直接保存即可。
    await expect(dlg.locator('.el-transfer')).toBeVisible()
    await expect(dlg.locator('.el-transfer-panel__item').first()).toBeVisible()
    const leftItems = dlg.locator('.el-transfer-panel').first().locator('.el-transfer-panel__item')
    const n = await leftItems.count()
    if (n >= 1) await leftItems.nth(0).click()
    if (n >= 2) await leftItems.nth(1).click()
    if (n >= 1) {
      // nth(1)=左→右(►)：把候选移入已关联；nth(0)=右→左(◄) 在右侧无勾选时恒 disabled。
      await dlg.locator('.el-transfer__button').nth(1).click().catch(() => {})
    }
    await dlg.getByRole('button', { name: '保存协调员' }).click()
    await expect(page.getByText('已更新批次协调员')).toBeVisible()
  })

  // PL/VL 无 batch.import：批次详情不得出现「+ 添加协调员」「+ 手动添加案件」——
  // 曾误门控为 proj.edit，PL 看得见按钮但点了必 403（UI 门控与契约 x-permission 不一致）。
  for (const role of ['PL', 'VL'] as const) {
    test(`${role} 下钻批次详情无「添加协调员」「手动添加案件」入口(无 batch.import)`, async ({ page }) => {
      await loginRole(page, role)
      await expect(page.getByRole('menuitem', { name: '撮合派单' })).toHaveCount(0)
      await openBatchDetail(page, role, undefined, 'props')
      await expect(page.getByRole('button', { name: '+ 添加协调员' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '+ 手动添加案件' })).toHaveCount(0)
    })
  }

  test('CO 无「撮合派单」菜单，直敲 /batches 与 /batches/1 均被守卫拦截', async ({ page }) => {
    await loginRole(page, 'CO')
    await expect(page.getByRole('menuitem', { name: '撮合派单' })).toHaveCount(0)
    await page.goto('/batches')
    await expect(page).not.toHaveURL(/\/batches/)
    await page.waitForLoadState('networkidle')
    await page.goto('/batches/1')
    await expect(page).not.toHaveURL(/\/batches/)
  })
})
