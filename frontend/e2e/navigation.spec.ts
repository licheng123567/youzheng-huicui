import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// 各后台屏真屏可达性：SA 登录后逐屏导航，断言路由切换且无崩溃（页面有内容卡片）。
// label 必须与 SA 侧栏真实菜单文案一致（src/constants/nav.ts 是单一真源）。
// 注：「通话记录」只在 PC/CO 菜单（一线作业角色），平台无此入口 → 不列入本表。
const SCREENS: { label: string; url: RegExp }[] = [
  { label: '项目管理', url: /\/projects/ },
  { label: '案件运营', url: /\/batches/ },      // v1.17.0 三合一：撮合派单+案件管理+平台公海（案件明细走批次下钻）
  { label: '结算对账', url: /\/settlement/ },   // v1.16.0 平台双线总账（收佣/付佣两菜单合并）
  { label: '质检/风控', url: /\/risks/ },
  { label: '经营报表', url: /\/reports/ },
  { label: '存证管理', url: /\/evidence/ },
  { label: '额度管理', url: /\/quota/ },     // v1.19.0 计费明细+充值中心合并
  { label: '参数配置', url: /\/settings/ },
  { label: '成员管理', url: /\/members/ },
]

test.describe('后台各屏真屏可达(SA)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, 'admin')
  })

  for (const s of SCREENS) {
    test(`导航到「${s.label}」屏正常加载`, async ({ page }) => {
      await page.getByRole('menuitem', { name: s.label }).click()
      await expect(page).toHaveURL(s.url)
      // 屏内有卡片/表格容器即视为渲染成功（ds-admin .card 或 ElementPlus 容器）
      await expect(page.locator('.card, .el-card, .el-table, .el-descriptions, table').first()).toBeVisible()
    })
  }
})
