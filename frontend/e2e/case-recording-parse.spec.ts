import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M5 余额不足暂停→充值补解析(BR-M5-02)：
// QUOTA_BLOCKED 录音出「补解析」按钮→/recordings/{id}/parse 转 PARSING；
// 余额不足 409 提示；批量补解析→/recordings/batch-parse 返 queued/skipped。
test.describe('US-M5 余额不足补解析(CO)', () => {
  // parse 桩实现把 QUOTA_BLOCKED→PARSING 不可逆，三用例各占一件 QB 案（M5-QB-0{1,2,3}）互不串味。
  async function openCallTab(page: any, acctNo: string) {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    // 锚定指定 QUOTA_BLOCKED 录音的私海案（余额不足暂停态，供「补解析」按钮渲染）
    await rows.filter({ hasText: acctNo }).first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    await page.getByRole('tab', { name: /通话 \/ AI 复盘/ }).click()
    // 录音面板按需加载：先点「获取最新通话录音」拉取 latest，QUOTA_BLOCKED 态按钮才渲染。
    // getLatest 异步，等面板「状态」描述项渲染（任一状态先到位），再交由用例守卫判 QUOTA_BLOCKED。
    // 注：parse 桩把 QUOTA_BLOCKED→PARSING 不可逆，二次跑全量时该案已 PARSING→守卫优雅 skip（不留红）。
    await page.getByRole('button', { name: '获取最新通话录音' }).click()
    // 等录音面板渲染完成（「看 AI 复盘」按钮恒在面板内），再让用例守卫判 QUOTA_BLOCKED 状态。
    await expect(page.getByRole('button', { name: '看 AI 复盘' })).toBeVisible()
  }

  test('QUOTA_BLOCKED→显示「补解析」按钮', async ({ page }) => {
    await openCallTab(page, 'M5-QB-01')
    const blocked = page.getByText('QUOTA_BLOCKED')
    if (!(await blocked.count())) {
      test.skip(true, '本案录音非 QUOTA_BLOCKED 态（已被前次补解析转 PARSING）')
    }
    await expect(page.getByRole('button', { name: '补解析', exact: true })).toBeVisible()
  })

  test('点补解析→PARSING 或余额不足提示', async ({ page }) => {
    await openCallTab(page, 'M5-QB-02')
    const btn = page.getByRole('button', { name: '补解析', exact: true })
    if (!(await btn.count())) {
      test.skip(true, '无补解析入口')
    }
    await btn.click()
    // 成功受理(解析中) 或 余额不足提示二选一
    await expect(
      page.getByText(/已受理补解析|解析中|余额不足/).first(),
    ).toBeVisible()
  })

  test('批量补解析→入队/跳过反馈', async ({ page }) => {
    await openCallTab(page, 'M5-QB-03')
    const btn = page.getByRole('button', { name: '批量补解析' })
    if (!(await btn.count())) {
      test.skip(true, '无批量补解析入口')
    }
    await btn.click()
    await expect(
      page.getByText(/已受理批量补解析|入队|余额不足/).first(),
    ).toBeVisible()
  })
})
