import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M9-06 物业只查看不确认/不驳回（矩阵§6 line111 PL/PC=◐只读）。
// 修 H-01：PL/PC 侧栏可见「结算」；进入后 side 锁 IN(收佣线)、对账三列有值非「—」(修 M-10)、
// 无「勾选明细生成支付申请单」按钮、PENDING 单无「确认收款/撤销」按钮(只读)。
test.describe('US-M9-06 物业结算只读(PL/PC)', () => {
  for (const role of ['PL', 'PC'] as const) {
    test(`${role} 侧栏可见结算并进入(修 H-01)`, async ({ page }) => {
      await loginRole(page, role)
      const menu = page.getByRole('menuitem', { name: '结算' })
      await expect(menu).toBeVisible()
      await menu.click()
      await expect(page).toHaveURL(/\/settlement/)
      await expect(page.getByText('对账汇总')).toBeVisible()
    })

    test(`${role} side 锁定 IN(收佣线)`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/settlement')
      await expect(page.getByText('对账汇总')).toBeVisible()
      // 物业仅 IN 线：OUT(付佣)切换不可见
      await expect(page.getByRole('radio', { name: /OUT|付佣/ })).toHaveCount(0)
    })

    test(`${role} 对账汇总三列有值非「—」(修 M-10)`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/settlement')
      const recon = page.locator('.el-table').first()
      await expect(recon).toBeVisible()
      const firstRow = recon.locator('.el-table__row').first()
      await expect(firstRow).toBeVisible()
      // 批次/比例/应结三列任一不得为占位「—」
      await expect(firstRow.getByText('—', { exact: true })).toHaveCount(0)
    })

    test(`${role} 无「生成支付申请单」按钮且 PENDING 单只读`, async ({ page }) => {
      await loginRole(page, role)
      await page.goto('/settlement')
      await expect(page.getByText('对账汇总')).toBeVisible()
      // 不出现勾选明细生成入口
      await expect(page.getByRole('button', { name: /生成支付申请单/ })).toHaveCount(0)
      // PENDING 单无确认收款/撤销写按钮
      await expect(page.getByRole('button', { name: /确认收款/ })).toHaveCount(0)
      await expect(page.getByRole('button', { name: /撤销/ })).toHaveCount(0)
    })
  }
})
