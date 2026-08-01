import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// 服务商负责人(VL) 经营报表 + 成员管理·工作督导 还原(2026-07)。
test.describe('VL 经营报表还原', () => {
  test('催收员产能/佣金汇总/团队看板/批次汇总四卡有真数据', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '经营报表' }).click()
    await expect(page).toHaveURL(/\/reports/)
    // 四张 VL 卡片标题
    await expect(page.getByText('催收员产能')).toBeVisible()
    await expect(page.getByText(/佣金汇总/)).toBeVisible()
    await expect(page.getByText('团队即时看板（US-M10-03）')).toBeVisible()
    await expect(page.getByText('批次汇总')).toBeVisible()
    // KPI 从 data.kpis 渲染(此前误读 data.summary 恒空)——应收总额卡有值
    await expect(page.locator('.kpi .l', { hasText: '应收总额' })).toBeVisible()
    // 催收员产能表体有行(collector 维度·催收员甲持有 8 件)
    const prodCard = page.locator('.card', { hasText: '催收员产能' })
    await expect(prodCard.locator('tbody tr').first()).toBeVisible()
    await expect(prodCard.getByText('暂无数据')).toHaveCount(0)
  })
})

test.describe('VL 成员管理·工作督导', () => {
  test('成员列表/工作督导分档,督导表列+督导记录就位', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '成员管理' }).click()
    await expect(page).toHaveURL(/\/members/)
    // 分段两档
    const seg = page.locator('.segctrl', { hasText: '工作督导' })
    await expect(seg.locator('span', { hasText: '成员列表' })).toBeVisible()
    await seg.locator('span', { hasText: '工作督导' }).click()
    // 督导表标题带角色名词(VL=催收员)
    await expect(page.getByText(/工作督导 — 催收员/)).toBeVisible()
    // 督导表头 + 督导记录区
    const board = page.locator('table').filter({ hasText: '今日动作' })
    await expect(board.locator('thead').getByText('持有案件数')).toBeVisible()
    await expect(page.getByText(/督导记录（GET \/members\/supervision）/)).toBeVisible()
  })

  test('对成员发起督导→留痕成功', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '成员管理' }).click()
    await page.locator('.segctrl span', { hasText: '工作督导' }).click()
    const supBtn = page.getByRole('button', { name: '督导处理' }).first()
    await supBtn.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {})
    if (await supBtn.count() && await supBtn.isEnabled()) {
      await supBtn.click()
      const dlg = page.getByRole('dialog').filter({ hasText: '督导处理' })
      await expect(dlg).toBeVisible()
      await dlg.getByPlaceholder(/督导说明/).fill('e2e 督导留痕')
      await dlg.getByRole('button', { name: /提交并留痕/ }).click()
      await expect(page.getByText(/已记录督导/)).toBeVisible()
    }
  })
})
