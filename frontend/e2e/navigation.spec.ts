import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// 各后台屏真屏可达性：SA 登录后逐屏导航，断言路由切换且无崩溃（页面有内容卡片）。
const SCREENS: { label: string; url: RegExp }[] = [
  { label: '项目', url: /\/projects/ },
  { label: '批次', url: /\/batches/ },
  { label: '公海', url: /\/sea/ },
  { label: '案件', url: /\/cases/ },
  { label: '结算', url: /\/settlement/ },
  { label: '质检', url: /\/risks/ },
  { label: '报表', url: /\/reports/ },
  { label: '存证', url: /\/evidence/ },
  { label: '计费', url: /\/billing/ },
  { label: '设置', url: /\/settings/ },
  { label: '成员', url: /\/members/ },
]

test.describe('后台各屏真屏可达(SA)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, 'admin')
  })

  for (const s of SCREENS) {
    test(`导航到「${s.label}」屏正常加载`, async ({ page }) => {
      await page.getByRole('menuitem', { name: s.label }).click()
      await expect(page).toHaveURL(s.url)
      // 屏内有 ElementPlus 卡片/表格容器即视为渲染成功，且无未捕获错误覆盖层
      await expect(page.locator('.el-card, .el-table, .el-descriptions').first()).toBeVisible()
    })
  }
})
