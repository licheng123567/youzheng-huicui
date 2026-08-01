import { test, expect } from './fixtures/test'
import { loginRole, openBatchDetail } from './helpers'

// BR-M2-18a/18b 批次级减免覆盖与恢复继承：
// 初始 source=INHERITED→自定义覆盖增 1 档→source=CUSTOM；恢复继承→回 INHERITED。
//
// UI 现状（ds-admin 改版后）：来源用两个 radio「继承项目 / 自定义覆盖」切换，不再是同名按钮；
//   选「自定义覆盖」→ 开 el-dialog「批次自定义减免…」→ 填折扣 →「保存覆盖」；
//   CUSTOM 态下另有「编辑阶梯 / 恢复继承」两个文字按钮；「恢复继承」走 ElMessageBox 确认。
//   来源不再用 el-tag 标签展示，而是「阶梯表格(CUSTOM) vs 继承说明文案(INHERITED)」二选一。
// PL/PC 无「撮合派单」菜单，经「案件管理」批次优先入口下钻（openBatchDetail 按角色选入口）。

const RD_CARD = '减免政策（批次级）'
const INHERIT_NOTE = '继承项目级减免政策（项目修改后自动跟随）。'

const reduceCard = (page: any) => page.locator('.card').filter({ hasText: RD_CARD })
const inheritRadio = (page: any) => reduceCard(page).getByText('继承项目', { exact: true })
const customRadio = (page: any) => reduceCard(page).getByText('自定义覆盖', { exact: true })

// 详情页减免档位异步加载(loadReduceTiers)；等其响应回来后 radio/表格才渲染，
// 否则随后立刻 count() 会因竞态读到 0 而误跳过。
async function openFirstBatch(page: any) {
  const rt = page.waitForResponse((r: any) => /\/batches\/\d+\/reduce-tiers/.test(r.url()))
  await openBatchDetail(page, 'PL', undefined, 'props')
  const response = await rt
  const source = (await response.json()).source
  await expect(reduceCard(page)).toBeVisible()
  if (source === 'CUSTOM') {
    await expect(reduceCard(page).getByRole('button', { name: '恢复继承' })).toBeVisible()
  } else {
    await expect(reduceCard(page).getByText(INHERIT_NOTE)).toBeVisible()
  }
}

/** 保证从 INHERITED 起步（用例可重复跑）。 */
async function ensureInherited(page: any) {
  const restore = reduceCard(page).getByRole('button', { name: '恢复继承' })
  if (await restore.count()) {
    await restore.click()
    await page.locator('.el-message-box').getByRole('button', { name: /OK|确定|确认/ }).click()
    await expect(page.getByText('已恢复继承项目默认减免')).toBeVisible()
  }
  await expect(reduceCard(page).getByText(INHERIT_NOTE)).toBeVisible()
}

test.describe('BR-M2-18a 批次减免覆盖(PL)', () => {
  test('自定义覆盖增 1 档→source 变批次自定义', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    await ensureInherited(page)

    await customRadio(page).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '批次自定义减免' })
    await expect(dlg).toBeVisible()
    await dlg.getByPlaceholder('如 9折').first().fill('85折')
    await dlg.getByRole('button', { name: '保存覆盖' }).click()
    await expect(page.getByText('已保存批次自定义减免')).toBeVisible()

    // CUSTOM → 渲染阶梯表格（含刚填的档），继承说明文案消失。
    await expect(reduceCard(page).locator('tbody')).toContainText('85折')
    await expect(reduceCard(page).getByText(INHERIT_NOTE)).toHaveCount(0)
  })

  test('恢复继承→回项目默认', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)

    // 构造 CUSTOM 前置态（本用例可独立运行）。
    if (!(await reduceCard(page).getByRole('button', { name: '恢复继承' }).count())) {
      await customRadio(page).click()
      const dlg = page.locator('.el-dialog').filter({ hasText: '批次自定义减免' })
      await expect(dlg).toBeVisible()
      await dlg.getByPlaceholder('如 9折').first().fill('9折')
      await dlg.getByRole('button', { name: '保存覆盖' }).click()
      await expect(page.getByText('已保存批次自定义减免')).toBeVisible()
    }

    await reduceCard(page).getByRole('button', { name: '恢复继承' }).click()
    // ElMessageBox 确认键：未配中文 locale 时渲染 OK/Cancel。
    await page.locator('.el-message-box').getByRole('button', { name: /OK|确定|确认/ }).click()
    await expect(page.getByText('已恢复继承项目默认减免')).toBeVisible()
    await expect(reduceCard(page).getByText(INHERIT_NOTE)).toBeVisible()
  })

  // 用 PC 而非 CO：减免政策主数据归 PL，PC 已收权(无 reduce.policy.edit)但仍可下钻批次详情；
  // CO 根本进不了批次详情(案件入口为私海/公海扁平清单)，无法在该页验证权限门控。
  test('无 reduce.policy.edit(PC)→只读，无「自定义覆盖」入口', async ({ page }) => {
    await loginRole(page, 'PC')
    const reduceRequests: string[] = []
    page.on('request', (request) => {
      if (/\/batches\/\d+\/reduce-tiers(?:\?|$)/.test(request.url())) reduceRequests.push(request.url())
    })
    await openBatchDetail(page, 'PC', undefined, 'props')
    await expect(reduceCard(page)).toBeVisible()
    expect(reduceRequests).toEqual([])
    const denied = reduceCard(page).getByText('无减免策略查看权限')
    await expect(denied).toBeVisible()
    await expect(customRadio(page)).toHaveCount(0)
    await expect(reduceCard(page).getByRole('button', { name: '编辑阶梯' })).toHaveCount(0)
    await expect(reduceCard(page).getByRole('button', { name: '恢复继承' })).toHaveCount(0)
  })
})
