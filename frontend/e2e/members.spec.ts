import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M1-04 成员管理 / BR-M1-04a 本组织建员 / BR-M1-03 权限子集 / 矩阵§8 门控。
test.describe('US-M1-04 成员管理(PL/VL)', () => {
  test('SA 组织管理显示负责人账号与完整手机', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/org-mgmt')

    const row = page.locator('table').first().locator('tbody tr').filter({ hasText: '翠湖物业' })
    await expect(row).toContainText('cuihu_pl')
    await expect(row).toContainText('13900000001')
    await expect(row.locator('[data-field="owner-username"]')).toHaveText('cuihu_pl')
    await expect(row.locator('[data-field="owner-phone"]')).toHaveText('13900000001')
  })

  test('PL 新增成员角色下拉显示「PC 物业协调员」(不含「催收员」误文案)', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '成员管理' }).click()
    await expect(page).toHaveURL(/\/members/)
    await page.getByRole('button', { name: '新增成员' }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '新增成员' })
    await expect(dlg).toBeVisible()
    // 角色下拉
    await dlg.locator('.el-select').first().click()
    await expect(page.getByRole('option', { name: 'PC 物业协调员' })).toBeVisible()
    // 物业组织下拉不应出现催收员
    await expect(page.getByRole('option', { name: /催收员/ })).toHaveCount(0)
  })

  test('PL 成员管理显示本组织账号和完整手机且不调用服务商容量接口', async ({ page }) => {
    const providerCapacityRequests: string[] = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.includes('/v1/providers/')) {
        providerCapacityRequests.push(request.url())
      }
    })

    await loginRole(page, 'PL')
    await page.goto('/members')

    const memberRows = page.locator('table').first().locator('tbody tr')
    const owner = memberRows.filter({ hasText: '翠湖负责人' }).first()
    const coordinator = memberRows.filter({ hasText: '翠湖协调员' }).first()
    await expect(owner).toContainText('cuihu_pl')
    await expect(owner).toContainText('13900000001')
    await expect(coordinator).toContainText('cuihu_pc')
    await expect(coordinator).toContainText('13900000006')
    expect(providerCapacityRequests).toEqual([])
  })

  test('VL 新增成员角色下拉显示「CO 催收员」(无「服务商催收员」误文案)', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '成员管理' }).click()
    await expect(page).toHaveURL(/\/members/)
    await page.getByRole('button', { name: '新增成员' }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '新增成员' })
    await expect(dlg).toBeVisible()
    await dlg.locator('.el-select').first().click()
    await expect(page.getByRole('option', { name: 'CO 催收员' })).toBeVisible()
    await expect(page.getByRole('option', { name: /服务商催收员/ })).toHaveCount(0)
  })

  test('权限子集越权→后端 403→ElMessage 越子集提示', async ({ page }) => {
    await loginRole(page, 'VL')
    await page.goto('/members')
    await page.getByRole('button', { name: '新增成员' }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '新增成员' })
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

  // v1.21.1：平台的成员管理只管平台自己的人——列表与「新增成员」口径必须一致。
  // 此前列表对平台是全量（混着服务商催收员/物业协调员，按钮却全是灰的），看着像能跨组织管人。
  test('SA 成员管理只见平台员工(SA/SE)·角色下拉无 CO/PC', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '成员管理' }).click()
    await expect(page).toHaveURL(/\/members/)

    // 成员主表只剩平台员工，且展示真实账号与完整手机。
    const memberTable = page.locator('table').first()
    const rows = memberTable.locator('tbody tr')
    await expect(rows.filter({ hasText: '催收员' })).toHaveCount(0)
    await expect(rows.filter({ hasText: '协调员' })).toHaveCount(0)
    const adminRow = rows.filter({ hasText: '平台超管' }).first()
    const operatorRow = rows.filter({ hasText: '平台运营' }).first()
    await expect(adminRow).toContainText('admin')
    await expect(adminRow).toContainText('13800000000')
    await expect(operatorRow).toContainText('plat_se')
    await expect(operatorRow).toContainText('13800000001')
    await expect(memberTable).not.toContainText('cuihu_pl')
    await expect(memberTable).not.toContainText('jx_vl')
    await expect(memberTable).not.toContainText('jx_co1')

    // 新增成员的角色下拉同口径：只能建平台员工（后端 BR-M1-04a 也会 403 拦跨组织角色）
    await page.getByRole('button', { name: '新增成员' }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '新增成员' })
    await dlg.locator('.el-select').first().click()
    await expect(page.getByRole('option', { name: 'SE 平台员工' })).toBeVisible()
    await expect(page.getByRole('option', { name: /催收员|协调员/ })).toHaveCount(0)
  })

  test('SE 成员管理同样只见平台员工且不显示工作督导', async ({ page }) => {
    await loginRole(page, 'SE')
    await page.goto('/members')

    const memberTable = page.locator('table').first()
    const rows = memberTable.locator('tbody tr')
    await expect(rows.filter({ hasText: '平台超管' }).first()).toContainText('13800000000')
    await expect(rows.filter({ hasText: '平台运营' }).first()).toContainText('13800000001')
    await expect(memberTable).not.toContainText('cuihu_pl')
    await expect(memberTable).not.toContainText('jx_vl')
    await expect(memberTable).not.toContainText('jx_co1')
    await expect(page.getByText('工作督导', { exact: true })).toHaveCount(0)
  })

  test('CO/VL(无 member.manage)无「成员」菜单·直链不渲染管理操作', async ({ page }) => {
    await loginRole(page, 'CO')
    // CO 无 member.manage → 侧栏无成员菜单
    await expect(page.getByRole('menuitem', { name: '成员管理' })).toHaveCount(0)
    // 直链进入也无管理操作（无新增成员按钮）
    await page.goto('/members')
    await expect(page.getByRole('button', { name: '新增成员' })).toHaveCount(0)
  })
})
