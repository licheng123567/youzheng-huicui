import { test, expect } from '@playwright/test'
import { loginRole, expectMenu } from './helpers'

// v1.21.0「短信通道 · 按组织管理」。
// 用户拍板：**签名与模板由平台统一配置，物业不能编辑**；模板由平台代向运营商报备。
// 故权限口径：写侧 = settings.manage（仅 SA 平台超管）；SE 平台运营同屏只读；
// PL 物业负责人只见自己组织且只读；CO 催收员无此菜单、直敲 URL 被弹开。
test.describe('v1.21.0 短信通道（按组织）', () => {

  test('SA 平台超管：组织列表 → 详情 → 改签名 → 建草稿 → 回填报备生效', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '短信通道' }).click()
    await expect(page).toHaveURL(/\/sms$/)

    // 一行一个物业组织
    const t = page.locator('table').first()
    for (const h of ['物业组织', '短信签名', '模板', '本月发送', '短信余额', '通道']) {
      await expect(t.locator('thead').getByText(h, { exact: true })).toBeVisible()
    }
    const row = t.locator('tbody tr', { hasText: '翠湖物业' }).first()
    await expect(row).toBeVisible()

    // 点进详情（两级下钻）
    await row.click()
    await expect(page).toHaveURL(/\/sms\/\d+/)
    await expect(page.getByText('返回组织列表', { exact: false })).toBeVisible()
    await expect(page.locator('.kpi', { hasText: '短信签名' })).toBeVisible()

    // 改签名 → 立即读回（SmsConfigService 不缓存，平台改了物业下一条短信就用新签名）
    await page.getByRole('button', { name: '编辑配置' }).click()
    const cd = page.getByRole('dialog')
    await cd.locator('.el-form-item', { hasText: '短信签名' }).locator('input').fill('【翠湖E2E】')
    await cd.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('已保存短信配置（立即生效）')).toBeVisible()
    await expect(page.locator('.kpi', { hasText: '短信签名' })).toContainText('【翠湖E2E】')

    // 新建草稿（DRAFT 绝不用于发送）。用「通知」用途：缴费链接的 ACTIVE 模板是种子数据，
    // 报备新的会把它自动归档，而发送链路的其他 spec 依赖它。
    await page.getByRole('button', { name: '+ 新建草稿' }).click()
    const td = page.getByRole('dialog')
    await td.locator('.el-form-item', { hasText: '用途' }).locator('.el-select').click()
    await page.locator('.el-select-dropdown__item', { hasText: '通知' }).first().click()
    await td.locator('.el-form-item', { hasText: '模板名称' }).locator('input').fill('E2E催缴模板')
    await td.locator('textarea').first().fill('您的物业费待缴，请点击 {0} 完成缴费')
    // 变量顺序默认 ['payUrl']，与正文的 1 个占位符对齐；数量不一致时报备生效会被 422 挡下（防错位）
    await expect(td.locator('.el-form-item', { hasText: '变量顺序' }).locator('.el-tag')).toContainText('payUrl')
    await td.getByRole('button', { name: '保存草稿' }).click()
    await expect(page.getByText(/草稿已创建/)).toBeVisible()

    // 重跑时上一轮的同名模板会以 ARCHIVED 留在表里（register 自动归档旧 ACTIVE）→ 按「名称 + 状态」定位
    const tplRow = page.locator('tbody tr').filter({ hasText: 'E2E催缴模板' }).filter({ hasText: '待报备' })
    await expect(tplRow).toBeVisible()

    // 回填报备 → ACTIVE（运营商模板ID 是 ACTIVE 的硬前提）
    await tplRow.getByText('回填报备', { exact: true }).click()
    const rd = page.getByRole('dialog')
    await rd.locator('.el-form-item', { hasText: '运营商模板ID' }).locator('input').fill('TPL_E2E_001')
    await rd.getByRole('button', { name: '确认回填' }).click()
    await expect(page.locator('tbody tr').filter({ hasText: 'E2E催缴模板' }).filter({ hasText: '已生效' })).toHaveCount(1)

    // 发送统计 & 明细：全量口径 KPI + 失败原因列（BR-M9-08 失败不退条数）
    await expect(page.getByText('发送统计 & 明细')).toBeVisible()
    await expect(page.locator('thead').getByText('失败原因', { exact: true })).toBeVisible()
    await expect(page.locator('.note').filter({ hasText: '失败不退条数但记原因' })).toBeVisible()
  })

  test('SE 平台运营：能看组织列表与详情，但无编辑入口（无 settings.manage）', async ({ page }) => {
    await loginRole(page, 'SE')
    await page.getByRole('menuitem', { name: '短信通道' }).click()
    await page.locator('tbody tr', { hasText: '翠湖物业' }).first().click()
    await expect(page).toHaveURL(/\/sms\/\d+/)
    await expect(page.locator('.kpi', { hasText: '短信签名' })).toBeVisible()
    await expect(page.getByRole('button', { name: '编辑配置' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '+ 新建草稿' })).toHaveCount(0)
  })

  test('PL 物业负责人：只见自己组织的只读视图（不落组织列表）', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '短信通道' }).click()
    await expect(page).toHaveURL(/\/sms$/)
    // 物业直接进详情，无组织列表、无编辑入口
    await expect(page.locator('.kpi', { hasText: '短信签名' })).toBeVisible()
    await expect(page.locator('.alert').first()).toContainText('平台统一配置')
    await expect(page.getByRole('button', { name: '编辑配置' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '+ 新建草稿' })).toHaveCount(0)
    await expect(page.getByText('返回组织列表', { exact: false })).toHaveCount(0)
  })

  test('CO 催收员：无短信通道菜单，直敲 /sms 被弹开', async ({ page }) => {
    await loginRole(page, 'CO')
    await expectMenu(page, '短信通道', false)
    await page.goto('/sms')
    await expect(page).not.toHaveURL(/\/sms$/)
  })
})
