import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// v1.22.0 停权口径（用户拍板）：
//   ① 个人停权 = **组织自己停**（VL 服务商负责人 / PL 物业负责人 在自己的成员管理里停用本组织员工），
//      平台只下「停用账号」的处理决定并跟踪回执；**归属方逾期 72h 未回执**，平台才可强制停用（兜底）。
//      此前平台复核选 DEACTIVATE 会当场 UPDATE account——越过组织停别人家的人，与 BR-M5-07 打架。
//   ② 组织级停权 = 平台的闸，且是**停新单不断存量**：停用后不能被派新单/不能新建项目·导入批次，
//      但成员照常登录、在催案件照常作业、结算照常（要收回案件走「批次结项」）。
test.describe('v1.22.0 停权口径', () => {

  test('SA 平台超管：组织可停用/启用（停用须填原因）', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '成员管理' }).click()

    // 组织管理表格（平台段）：非平台组织才有停用按钮
    const orgTable = page.locator('table').filter({ hasText: '负责人账号' })
    const row = orgTable.locator('tbody tr').filter({ hasText: '捷信催收' }).first()
    await expect(row).toBeVisible()

    // 平台自身不给停用入口（后端也会 409）。注意：按钮与状态标签文案都是「停用」，
    // 必须按 role=button 定位——按文本会撞上状态标签（点 span 不弹框，正是本用例第一版挂掉的原因）。
    const platformRow = orgTable.locator('tbody tr').filter({ hasText: '有证平台' }).first()
    await expect(platformRow.getByRole('button', { name: /^(停用|启用)$/ })).toHaveCount(0)

    // 自愈：上一轮若留在停用态，先恢复，保证本用例可重复跑
    if (await row.getByRole('button', { name: '启用' }).count()) {
      await row.getByRole('button', { name: '启用' }).click()
      await page.locator('.el-message-box').getByRole('button', { name: '确定' }).click()
      await expect(page.getByText(/已恢复启用/)).toBeVisible()
    }

    await row.getByRole('button', { name: '停用' }).click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('不再接受新派单')        // 语义必须写在弹窗里：停新单不断存量
    await expect(box).toContainText('在催案件与结算不受影响')
    await box.locator('input').fill('E2E 合规抽检不通过')
    await box.getByRole('button', { name: '确定' }).click()
    await expect(page.getByText(/已停用/)).toBeVisible()
    await expect(row.getByRole('button', { name: '启用' })).toBeVisible()

    // 恢复启用（还原库，spec 可重复跑）
    await row.getByRole('button', { name: '启用' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确定' }).click()
    await expect(page.getByText(/已恢复启用/)).toBeVisible()
  })

  test('VL 服务商负责人：能停用本组织催收员（个人停权归组织自己）', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '成员管理' }).click()
    const row = page.locator('tbody tr').filter({ hasText: 'CO 催收员' }).first()
    await expect(row).toBeVisible()
    // 停用/启用按钮对本组织成员可用（负责人自己不可停——isOwner 行按钮 disabled）
    await expect(row.getByRole('button', { name: /停用|启用/ })).toBeEnabled()
  })

  test('平台处置任务：未逾期不给强制停用入口（谁的员工谁处置）', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '质检/风控' }).click()
    await expect(page.getByText('处置任务跟踪')).toBeVisible()

    // 平台侧操作列存在；未逾期的任务只显示「待归属方处理」，不给强停按钮
    const tbl = page.locator('table').filter({ hasText: '处理决定' })
    const pending = tbl.locator('tbody tr').filter({ hasText: '待归属方处理' })
    if (await pending.count()) {
      await expect(pending.first().getByRole('button', { name: '强制停用' })).toHaveCount(0)
    }
  })
})
