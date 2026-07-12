import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// v1.25.0 平台经营报表穿透（用户诉求：「可以根据物业公司的聚合向下穿透统计，也可以根据服务商穿透统计」）。
// 此前平台视角是两张写死「暂无数据」的空表（区域损益/佣金毛利）+ 一张批次平表——看不出哪家贡献多少，也钻不下去。
//
// 最要紧的断言是**账要对得上**：下钻后的合计必须与上一层那一行严丝合缝。
// 服务商链路尤其容易错：口径是双侧的（在催盘子认当前归属、催回的钱认 V914 到账快照），
// 若下钻时用「案件当前归属」一把滤，被结项过的服务商其历史回款会凭空蒸发（实测捷信 19,700 → 10,100）。
test.describe('v1.25.0 平台经营报表穿透', () => {

  const money = (t: string) => Number(t.replace(/[¥,\s]/g, '')) || 0
  /** 穿透卡片内部的 KPI（页首还有一组全局 KPI，别撞上） */
  const drillKpi = (page: import('@playwright/test').Page, label: string) =>
    page.getByTestId('drill-kpis').locator('.kpi').filter({ hasText: label }).locator('.n')
  /** 点行后表头会立刻变（curCrumb 是同步的），但 KPI 要等接口回来——必须轮询，不能一次性读。 */
  const expectDrillKpi = async (page: import('@playwright/test').Page, label: string, expected: number) => {
    await expect.poll(async () => money(await drillKpi(page, label).innerText()), { timeout: 8000 })
      .toBe(expected)
  }

  test('SA：按物业公司聚合 → 钻到项目 → 钻到批次，逐层合计对得上', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '经营报表' }).click()
    await expect(page.getByText('穿透统计')).toBeVisible()

    // 根层：各物业公司
    await expect(page.locator('thead').getByText('物业公司', { exact: true })).toBeVisible()
    const cuihu = page.locator('tbody tr').filter({ hasText: '翠湖物业' }).first()
    await expect(cuihu).toBeVisible()
    const dueAtRoot = money(await cuihu.locator('td').nth(1).innerText())
    const repayAtRoot = money(await cuihu.locator('td').nth(2).innerText())
    expect(dueAtRoot).toBeGreaterThan(0)

    // 钻进翠湖 → 它的项目；KPI 合计必须等于上一层那一行
    await cuihu.click()
    await expect(page.locator('thead').getByText('项目', { exact: true })).toBeVisible()
    await expect(page.getByText('翠湖物业', { exact: true }).first()).toBeVisible()   // 面包屑
    await expectDrillKpi(page, '应收总额', dueAtRoot)
    await expectDrillKpi(page, '回款总额', repayAtRoot)

    // 再钻一层 → 批次
    const proj = page.locator('tbody tr').first()
    const projDue = money(await proj.locator('td').nth(1).innerText())
    await proj.click()
    await expect(page.locator('thead').getByText('批次', { exact: true })).toBeVisible()
    await expectDrillKpi(page, '应收总额', projDue)

    // 面包屑回退到根
    await page.getByRole('link', { name: '全部物业公司' }).or(page.getByText('全部物业公司')).first().click()
    await expect(page.locator('thead').getByText('物业公司', { exact: true })).toBeVisible()
  })

  test('SA：按服务商穿透——已结项的历史回款不会蒸发（钱认到账快照）', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '经营报表' }).click()
    await page.getByText('按服务商', { exact: true }).click()
    await expect(page.locator('thead').getByText('服务商', { exact: true })).toBeVisible()

    const prov = page.locator('tbody tr').filter({ hasText: '捷信催收' }).first()
    const dueAtRoot = money(await prov.locator('td').nth(1).innerText())
    const repayAtRoot = money(await prov.locator('td').nth(2).innerText())
    expect(repayAtRoot).toBeGreaterThan(0)

    await prov.click()
    await expect(page.locator('thead').getByText('批次', { exact: true })).toBeVisible()

    // 下钻合计 == 聚合行（若下钻按「案件当前归属」滤，被结项批次的历史回款会丢，这里就会挂）
    await expectDrillKpi(page, '应收总额', dueAtRoot)
    await expectDrillKpi(page, '回款总额', repayAtRoot)

    // 口径说明必须写在页面上——否则「应收 0 / 已回款 >0」的行看着像脏数据
    await expect(page.getByText(/在催盘子按当前归属、催回的钱按到账快照/)).toBeVisible()
  })

  // v1.25.1 佣金双线：用户诉求「按物业公司列表要有 应收/已收/待收佣金、应付/已付/待付佣金」。
  // 最要紧的不是列出来，而是**和【结算对账】页对得上**——同一笔钱在两个页面给两个数是最伤信任的 bug。
  test('SA：物业列表的佣金六列，与结算对账页逐批对得上', async ({ page, request }) => {
    // 权威口径直接问后端要（/recon/rollup-dual 是结算对账页的数据源）——别去猜前端把 token 存哪儿。
    const login = await request.post('http://localhost:9091/v1/auth/login', {
      data: { loginType: 'password', username: 'admin', password: 'Admin@123' },
    })
    const token = (await login.json()).token
    const res = await request.get('http://localhost:9091/v1/recon/rollup-dual?page=1&size=50', {
      headers: { Authorization: `Bearer ${token}` },
    })
    const items = (await res.json()).items ?? []
    const ledger = items.reduce((a: any, x: any) => ({
      inDue: a.inDue + (x.dueInCents ?? 0), inSettled: a.inSettled + (x.settledInCents ?? 0),
      outDue: a.outDue + (x.dueOutCents ?? 0), outSettled: a.outSettled + (x.settledOutCents ?? 0),
    }), { inDue: 0, inSettled: 0, outDue: 0, outSettled: 0 })
    expect(ledger.inDue).toBeGreaterThan(0)   // 种子里必须有佣金，否则这条断言等于没测

    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '经营报表' }).click()
    await page.getByText('佣金双线', { exact: true }).click()
    await expect(page.locator('thead').getByText('应收佣金', { exact: true })).toBeVisible()

    const sumCol = async (header: string) => {
      const hs = await page.locator('thead th').allInnerTexts()
      const idx = hs.findIndex((h) => h.trim() === header)
      const cells = await page.locator('tbody tr').locator(`td:nth-child(${idx + 1})`).allInnerTexts()
      return cells.reduce((a, t) => a + money(t), 0)
    }
    // 报表按物业维聚合，全平台合计必须与总账逐分一致（同一笔钱两个页面两个数=最伤信任的 bug）
    expect(await sumCol('应收佣金')).toBe(ledger.inDue / 100)
    expect(await sumCol('已收佣金')).toBe(ledger.inSettled / 100)
    expect(await sumCol('应付佣金')).toBe(ledger.outDue / 100)
    expect(await sumCol('已付佣金')).toBe(ledger.outSettled / 100)
    // 待收/待付 = 应收/应付 − 已收/已付（派生，不是另算一遍）
    expect(await sumCol('待收佣金')).toBe((ledger.inDue - ledger.inSettled) / 100)
    expect(await sumCol('待付佣金')).toBe((ledger.outDue - ledger.outSettled) / 100)

    await expect(page.getByText(/口径与【结算对账】页逐字一致/)).toBeVisible()
  })

  test('PL 物业负责人：看不到平台穿透（那是平台口径）', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '经营报表' }).click()
    await expect(page.getByText('穿透统计')).toHaveCount(0)
  })
})
