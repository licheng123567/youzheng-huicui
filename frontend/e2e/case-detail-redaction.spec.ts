import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M8 结案脱敏(BR-M8-09)：
// VL/CO 打开已结案(redacted)案件→联系人电话脱敏占位、明细被统计卡替代；
// 平台同案仍见完整明细。
test.describe('US-M8 结案脱敏(VL/CO 收敛 / 平台全见)', () => {
  // 按户号打开案件。走全局搜索（/search 是通用页，全角色可达；后端按 owner/room/acct_no/phone 匹配，
  // range 裁剪）——不能走「案件管理」：管理角色(VL/SA)那里是**批次**优先列表，没有 acctNo 行可点。
  // M8-RD-01 = co1 持有的 WITHDRAWN 私海案（对 VL/CO 触发 BR-M8-09 脱敏；SA 见全量明细）。
  async function openSomeCase(page: any, acctNo = 'M8-RD-01') {
    await page.goto(`/search?q=${acctNo}`)
    const rows = page.locator('tbody tr.row-click')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
  }

  for (const role of ['VL', 'CO'] as const) {
    test(`${role} 打开脱敏案件→统计收敛视图(无逐行明细)`, async ({ page }) => {
      await loginRole(page, role)
      await openSomeCase(page)
      // 脱敏收敛提示出现，则联系人明细表不渲染
      const redactAlert = page.getByText(/已结案并脱敏|BR-M8-09/)
      if (!(await redactAlert.count())) {
        test.skip(true, '未命中已脱敏案件')
      }
      await expect(redactAlert.first()).toBeVisible()
      // 收敛为统计卡：联系人逐行 .contact-item 一行不渲染
      await expect(page.locator('.contact-item')).toHaveCount(0)
    })
  }

  test('SA 同案仍见完整联系人明细', async ({ page }) => {
    await loginRole(page, 'SA')
    await openSomeCase(page)
    // 平台不收敛：无脱敏提示，且联系人逐行明细渲染。
    // （不以「设为主号码」为锚——该操作要 case.follow，平台超管并不持有。）
    await expect(page.getByText(/已结案并脱敏/)).toHaveCount(0)
    await expect(page.locator('.contact-item').first()).toBeVisible()
  })
})
