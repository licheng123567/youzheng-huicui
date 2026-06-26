import { test, expect, type Page } from '@playwright/test'
import { loginRole } from './helpers'

// US-M3-05 服务商负责人分配案件(批量, BR-M3-25/06)：
// VL 进服务商公海，多选 N 件批量分配给同一催收员（POST /cases/assign-batch）。
// 真实现(SeaView.vue)：批量分配弹窗只有「催收员 id」输入框 + 「按余量均摊」开关 + 「分配」按钮，
//   没有可点选的推荐表（推荐表只在「单案指派」弹窗 adlg 里）。后端 requireOwnCollector：
//   collectorId 必须是本商在岗 CO 的 account.id，否则整请求 403——故必须填真实 collectorId。
// 取真实 collectorId 的办法：先点某行「指派」开单案弹窗，它会调 /collector-capacity 自动回填
//   推荐催收员 id 到输入框，读出后关闭，再用于批量弹窗。

/** 通过单案「指派」弹窗发现一个真实可用的本商催收员 account.id，读出后关闭弹窗。 */
async function discoverCollectorId(page: Page): Promise<string> {
  const assignBtn = page.locator('.el-table__row').first().getByRole('button', { name: '指派' })
  await assignBtn.click()
  const adlg = page.locator('.el-dialog').filter({ hasText: '指派催收员' })
  await expect(adlg).toBeVisible()
  // 弹窗按余量推荐并把推荐者 id 自动回填到「催收员 id」输入框（BR-M3-23）。
  const input = adlg.getByRole('textbox')
  await expect(input).not.toHaveValue('', { timeout: 10_000 })
  const id = await input.inputValue()
  await adlg.getByRole('button', { name: '取消' }).click()
  await expect(adlg).toBeHidden()
  return id
}

test.describe('US-M3-05 服务商批量分配(VL)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'VL')
    await page.getByRole('menuitem', { name: '公海' }).click()
    await expect(page).toHaveURL(/\/sea/)
    await page.getByText('服务商公海', { exact: true }).click()
    await expect(page.locator('.el-table').first()).toBeVisible()
  })

  test('多选批量分配给同一催收员', async ({ page }) => {
    // 行多选框（type=selection）；服务商公海实测 2 件可批量。
    const checks = page.locator('.el-table__row .el-checkbox')
    if ((await checks.count()) < 2) {
      test.skip(true, '服务商公海可分配案件不足以批量')
    }
    // 先取一个真实 collectorId（批量弹窗无推荐表、需手填真实 id 否则后端 403）。
    const collectorId = await discoverCollectorId(page)

    await checks.nth(0).click()
    await checks.nth(1).click()
    // 批量分配入口：文案带已选计数「批量分配（已选 N）」。
    const batchBtn = page.getByRole('button', { name: /批量分配/ })
    await expect(batchBtn).toBeVisible()
    await batchBtn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '批量分配' })
    await expect(dlg).toBeVisible()
    // 填催收员 id（真实现：输入框，非点选推荐行）。
    await dlg.getByRole('textbox').fill(collectorId)
    await dlg.getByRole('button', { name: '分配', exact: true }).click()
    // 成功 toast：submitBatchAssign 触发 ElMessage.success(`已分配 N 件，被拒 M 件`)。
    await expect(page.getByText(/已分配\s*\d+\s*件/).first()).toBeVisible()
  })

  test('批量分配结果明细面板（成功/被拒分项）', async ({ page }) => {
    // 真实现：assign-batch 返回 {assigned[],rejected[]}，弹窗内渲染「分配结果」面板
    //   （成功 N / 被拒 M 标签；被拒明细表含 案件 + 拒绝原因）。
    // 注：当前 DevSeeder holdCap=50、服务商公海仅 2 件 → 无法构造真实超额，rejected 必为空。
    //   故此处断言可达的真实观测：批量分配后弹出「分配结果」面板并展示成功/被拒分项标签。
    //   若需真正验证「超持有上限被拒(BIZ_HOLD_CAP)」明细，需补种子（见返回说明）。
    const checks = page.locator('.el-table__row .el-checkbox')
    if ((await checks.count()) < 1) {
      test.skip(true, '无可分配案件')
    }
    const collectorId = await discoverCollectorId(page)

    // 全选触发批量。
    const selectAll = page.locator('.el-table__header .el-checkbox').first()
    if (await selectAll.count()) await selectAll.click()
    const batchBtn = page.getByRole('button', { name: /批量分配/ })
    await expect(batchBtn).toBeVisible()
    await batchBtn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '批量分配' })
    await expect(dlg).toBeVisible()
    await dlg.getByRole('textbox').fill(collectorId)
    await dlg.getByRole('button', { name: '分配', exact: true }).click()
    // 结果面板：弹窗内出现「分配结果」分割线 + 成功/被拒计数标签。
    await expect(dlg.getByText('分配结果')).toBeVisible()
    await expect(dlg.getByText(/成功\s*\d+/)).toBeVisible()
    await expect(dlg.getByText(/被拒\s*\d+/)).toBeVisible()
  })
})
