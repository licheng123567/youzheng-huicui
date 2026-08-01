import { test, expect, type Page } from './fixtures/test'
import { loginRole } from './helpers'

// US-M3-02 平台公海二选一处置(再派分支) + BR-M3-16：
// 无原退回方的 S0 案件可再派有效服务商；被服务商 X 退回的案件再选 X 时，
// 服务端护栏①必须拒绝并提示原因。每个成功再派都会占用目标服务商容量，故同一基线只做一次成功写入。
async function redispatchRoom(page: Page, room: string) {
  const roomCell = page.getByRole('cell', { name: room, exact: true })
  await expect(roomCell).toBeVisible()
  await roomCell.locator('..').getByRole('button', { name: '再派', exact: true }).click()

  const dlg = page.getByRole('dialog').filter({ hasText: '单案再派' })
  await expect(dlg).toBeVisible()
  await dlg.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '捷信催收' }).click()

  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && /\/v1\/cases\/[^/]+\/redispatch$/.test(new URL(response.url()).pathname),
  )
  await dlg.getByRole('button', { name: '再派', exact: true }).click()
  return { dlg, response: await responsePromise }
}

test.describe('US-M3-02 平台公海再派(SA)', () => {
  test.beforeEach(async ({ page, allowHttpFailure }) => {
    allowHttpFailure({
      method: 'POST',
      path: /^\/v1\/cases\/[^/]+\/redispatch$/,
      statuses: [409],
    })
    await loginRole(page, 'SA')
    // 平台公海已并入「撮合派单」页的 Tab（保留一个菜单入口）。
    await page.getByRole('menuitem', { name: '案件运营' }).click()
    await expect(page).toHaveURL(/\/batches/)
    // 顶部第一个 segctrl 是页级 Tab（批次派单/平台公海）；切到平台公海 → 内嵌 SeaView 自动落平台池。
    await page.locator('.segctrl').first().getByText('平台公海').click()
    await expect(page.locator('table').first()).toBeVisible()
    // 切池触发异步 load()，旧(服务商)表恒 visible 不会等到平台池数据；
    // 平台公海每行「来源池」列 .el-tag 文案=平台公海，等它出现确保已渲染平台池数据(再派按钮才就位)。
    await expect(page.locator('tbody tr .tag', { hasText: '平台公海' }).first()).toBeVisible()
  })

  test('无原退回方的待派案件→再派有效服务商成功', async ({ page }) => {
    const { dlg, response } = await redispatchRoom(page, 'S0-101')
    expect(response.status()).toBe(200)
    await expect(dlg).toBeHidden()
  })

  test('对同案再选原退回方X→护栏①拒绝并提示原因', async ({ page }) => {
    const { dlg, response } = await redispatchRoom(page, 'RET-101')
    expect(response.status()).toBe(409)
    await expect(dlg).toBeVisible()
    await expect(
      page.locator('.el-message').filter({ hasText: /不可再派回原退回服务商|已停用/ }).last(),
    ).toBeVisible()
  })
})
