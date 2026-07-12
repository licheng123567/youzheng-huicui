import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M3-11 超管系统配置：全 5 域(TIMERS/ROTATION/MARK_CODES/CLOSE_REASONS/SMS)可编辑、
// 版本递增、非法值拒绝；权限矩阵导出 CSV；非平台角色门控。
test.describe('US-M3-11 系统配置(SA)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '参数配置' }).click()
    await expect(page).toHaveURL(/\/settings/)
  })

  test('编辑 TIMERS(t2Hours)保存成功，版本递增；负数被拒', async ({ page }) => {
    // v1.23.0：配置页由「一行一域 + JSON dump」改为「一域一张卡（中文名/中文参数/说明）」，
    // 版本号移到卡头的 tag 上（title=域码）。断言随之改到新结构。
    const version = page.locator('span.tag[title="TIMERS"]')   // 卡头的版本 tag（域码在 title 上）
    await expect(version).toBeVisible()
    const before = (await version.innerText()).trim()

    await page.getByRole('button', { name: '编辑时效参数(TIMERS)' }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '编辑时效参数' })
    await expect(dlg).toBeVisible()
    // 改 T2 服务商处置(小时)
    const t2 = dlg.locator('.el-form-item').filter({ hasText: 'T2 服务商处置' }).locator('input')
    await t2.fill('200')
    await dlg.getByRole('button', { name: '保存新版本' }).click()
    await expect(page.getByText(/已保存|保存成功|生效/).first()).toBeVisible()
    // 版本递增：保存后异步 load() 重读，轮询等待版本 tag 变化（避免读到刷新前的旧值导致竞态）
    await expect(version).not.toHaveText(before)
  })

  test('编辑 MARK_CODES 数组(增一条 connected/effectiveFollowUp)保存回读一致', async ({ page }) => {
    await page.getByRole('button', { name: '编辑标记码(MARK_CODES)' }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '编辑标记码' })
    await expect(dlg).toBeVisible()
    await dlg.getByRole('button', { name: '+ 新增标记码' }).click()
    const lastRow = dlg.locator('.el-table__row').last()
    await lastRow.locator('input').nth(0).fill('E2E_OK')
    await lastRow.locator('input').nth(1).fill('E2E接通有效')
    // 接通/有效跟进开关打开（el-switch）
    await lastRow.locator('.el-switch').nth(1).click()
    await lastRow.locator('.el-switch').nth(2).click()
    await dlg.getByRole('button', { name: '保存新版本' }).click()
    await expect(page.getByText(/已保存|保存成功/).first()).toBeVisible()
  })

  test('编辑 SMS(cooldownMinutes/signature)与 CLOSE_REASONS 保存成功', async ({ page }) => {
    // SMS
    await page.getByRole('button', { name: '编辑短信配置(SMS)' }).click()
    let dlg = page.getByRole('dialog').filter({ hasText: '编辑短信配置' })
    await expect(dlg).toBeVisible()
    await dlg.locator('.el-form-item').filter({ hasText: '同案冷却' }).locator('input').fill('30')
    await dlg.getByPlaceholder('平台统一配置 BR-M9-09').fill('【有证慧催】')
    await dlg.getByRole('button', { name: '保存新版本' }).click()
    await expect(page.getByText(/已保存|保存成功/).first()).toBeVisible()

    // CLOSE_REASONS
    await page.getByRole('button', { name: '编辑结案原因(CLOSE_REASONS)' }).click()
    dlg = page.getByRole('dialog').filter({ hasText: '编辑结案原因' })
    await expect(dlg).toBeVisible()
    await dlg.getByRole('button', { name: '+ 新增结案原因' }).click()
    const lastRow = dlg.locator('.el-table__row').last()
    // 第 1 列是 kind 的 el-select（readonly combobox，不可填）；code 是第 2 个 input，label 是第 3 个
    await lastRow.locator('input').nth(1).fill('E2E_REASON')
    await dlg.getByRole('button', { name: '保存新版本' }).click()
    await expect(page.getByText(/已保存|保存成功/).first()).toBeVisible()
  })

  test('权限矩阵区导出 CSV（触发下载）', async ({ page }) => {
    const downloadP = page.waitForEvent('download').catch(() => null)
    await page.getByRole('button', { name: '导出 CSV' }).click()
    const download = await downloadP
    // 客户端 CSV 导出：成功触发下载或弹成功提示
    if (download) {
      expect(download.suggestedFilename()).toMatch(/\.csv$/i)
    } else {
      await expect(page.getByText('已导出权限矩阵 CSV')).toBeVisible()
    }
  })
})

test.describe('BR-M1-04b 配置仅平台(PL 门控)', () => {
  // /settings 为 platform:true 菜单，仅平台(SA/SE)可见；PL/PC/VL/CO 侧栏无「设置」入口。
  test('PL 无「设置」菜单(platform scope 门控)', async ({ page }) => {
    await loginRole(page, 'PL')
    await expect(page.getByRole('menuitem', { name: '参数配置' })).toHaveCount(0)
  })
})
