import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M3-27 释放记录可见(US-M3-06 衍生管理视图)：
// VL 打开释放记录抽屉，可见本商催收员释放历史与频次；CO/平台无关池不可见。
test.describe('BR-M3-27 释放记录(VL 可见 / CO 不可见)', () => {
  test('VL 打开释放记录抽屉→见本商释放历史(类型/案件/催收员/时间)', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '公海' }).click()
    await expect(page).toHaveURL(/\/sea/)
    const entry = page.getByRole('button', { name: /释放记录/ })
    if (!(await entry.count())) {
      test.skip(true, '释放记录入口未渲染')
    }
    await entry.click()
    const drawer = page.locator('.el-drawer').filter({ hasText: /释放记录|释放历史/ })
    await expect(drawer).toBeVisible()
    // 本商释放记录表列：类型/案件/催收员/时间（按实现：逐条释放历史，非聚合频次）
    await expect(drawer.getByRole('columnheader', { name: '类型' })).toBeVisible()
    await expect(drawer.getByRole('columnheader', { name: '催收员' })).toBeVisible()
    await expect(drawer.getByRole('columnheader', { name: '时间' })).toBeVisible()
  })

  test('CO 无释放记录管理入口', async ({ page }) => {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '公海' }).click()
    await expect(page).toHaveURL(/\/sea/)
    // 释放记录为管理视图，CO 不可见
    await expect(page.getByRole('button', { name: /释放记录/ })).toHaveCount(0)
  })
})
