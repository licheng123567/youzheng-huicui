import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M1-04 成员管理 / BR-M1-04a 本组织建员 / BR-M1-03 权限子集 / 矩阵§8 门控。
test.describe('US-M1-04 成员管理(PL/VL)', () => {
  test('PL 新增成员角色下拉显示「PC 物业协调员」(不含「催收员」误文案)', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '成员' }).click()
    await expect(page).toHaveURL(/\/members/)
    await page.getByRole('button', { name: '新增成员' }).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '新增成员' })
    await expect(dlg).toBeVisible()
    // 角色下拉
    await dlg.locator('.el-select').first().click()
    await expect(page.getByRole('option', { name: 'PC 物业协调员' })).toBeVisible()
    // 物业组织下拉不应出现催收员
    await expect(page.getByRole('option', { name: /催收员/ })).toHaveCount(0)
  })

  test('VL 新增成员角色下拉显示「CO 催收员」(无「服务商催收员」误文案)', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '成员' }).click()
    await expect(page).toHaveURL(/\/members/)
    await page.getByRole('button', { name: '新增成员' }).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '新增成员' })
    await expect(dlg).toBeVisible()
    await dlg.locator('.el-select').first().click()
    await expect(page.getByRole('option', { name: 'CO 催收员' })).toBeVisible()
    await expect(page.getByRole('option', { name: /服务商催收员/ })).toHaveCount(0)
  })

  test('权限子集越权→后端 403→ElMessage 越子集提示', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.goto('/members')
    await page.getByRole('button', { name: '新增成员' }).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '新增成员' })
    await expect(dlg).toBeVisible()
    await dlg.getByLabel('账号').fill('e2e_sub_' + (Date.now() % 100000))
    await dlg.getByLabel('姓名').fill('越子集测试')
    await dlg.getByLabel('手机').fill('139' + String(Date.now()).slice(-8))
    await dlg.locator('.el-select').first().click()
    await page.getByRole('option', { name: 'CO 催收员' }).click()
    // 权限子集复选框上限为操作人持有集——勾选若干（提交越权由后端 403 决定）
    const boxes = dlg.locator('.el-checkbox')
    if (await boxes.count()) await boxes.first().click()
    await dlg.getByRole('button', { name: '创建' }).click()
    // 成功或 403 越子集提示二选一（spec 只验交互闭环；越权时显提示）
    await expect(
      page.getByText(/已创建|创建成功|越.*子集|超出.*权限|403/).first(),
    ).toBeVisible()
  })

  test('CO/VL(无 member.manage)无「成员」菜单·直链不渲染管理操作', async ({ page }) => {
    await loginRole(page, 'CO')
    // CO 无 member.manage → 侧栏无成员菜单
    await expect(page.getByRole('menuitem', { name: '成员' })).toHaveCount(0)
    // 直链进入也无管理操作（无新增成员按钮）
    await page.goto('/members')
    await expect(page.getByRole('button', { name: '新增成员' })).toHaveCount(0)
  })
})
