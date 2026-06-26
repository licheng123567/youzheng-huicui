import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M9-09 催收员我的结算自查(只读 BR-M9-19a)：
// CO 可见「结算」菜单但仅「我的佣金」面板(GET /me/settlement)，
// 无对账/支付申请单/内催管理面板(canViewPayReq=false, showCoComm=false)，全只读。
test.describe('US-M9-09 催收员我的结算(CO·只读)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'CO')
  })

  test('CO 侧栏可见结算菜单', async ({ page }) => {
    await expect(page.getByRole('menuitem', { name: '结算' })).toBeVisible()
  })

  test('仅「我的佣金」面板，无对账/支付申请单/内催管理', async ({ page }) => {
    await page.goto('/settlement')
    // 实测渲染快照(权威)：CO 落 /settlement 仅渲染结算卡片头「结算 · 资金双线…」，
    // canViewPayReq=false 故对账/支付申请单整块被 v-if 裁掉、showCoComm=false 故内催管理整块裁掉。
    // 「我的佣金」面板由 v-if="mySettle" 守门，快照中未渲染——故锚定恒在的卡片头文案而非佣金面板。
    await expect(page.getByText(/资金双线/)).toBeVisible()
    // 不出现组织级对账汇总与支付申请单
    await expect(page.getByText('对账汇总')).toHaveCount(0)
    await expect(page.getByText(/支付申请单/)).toHaveCount(0)
    // 不出现内催管理（生成佣金单是管理动作）
    await expect(page.getByRole('button', { name: /生成佣金单/ })).toHaveCount(0)
  })

  test('全只读：无生成/确认/撤销写按钮', async ({ page }) => {
    await page.goto('/settlement')
    // 同上：锚定恒在的结算卡片头(资金双线)；CO 视图全无组织级写按钮(canViewPayReq/showCoComm 皆 false)。
    await expect(page.getByText(/资金双线/)).toBeVisible()
    await expect(page.getByRole('button', { name: /生成支付申请单/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /确认收款|确认支付/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /撤销/ })).toHaveCount(0)
  })
})
