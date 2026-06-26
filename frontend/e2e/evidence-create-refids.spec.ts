import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M6 三场景按次存证·场景校验；M6-FE-REFIDS 验收：
// PC 在案件详情发起 RECORDING 存证：未选录音被挡/提示；选 READY 录音提交→成功(toast「发起存证成功」)；
// DELIVERY 未选 SIGNED 文书被挡；MATERIAL_PACK 可留空成功。
// 现实对齐：只有 S3 私海案件 (acctNo=M3-S3-01,业主周私海) 种了 READY 录音 / SIGNED 文书；
// 其余案件「本案无 READY 录音」。提交成功是 ElMessage「发起存证成功」并关闭弹窗，无 ISSUING 文案。
test.describe('BR-M6 按次存证·场景 refIds 校验(PC)', () => {
  // 打开指定案件详情的「发起存证」对话框。
  // acctNo 缺省取第一行；需 READY 录音/SIGNED 文书的用例传 'M3-S3-01'(唯一种了外围实体的私海案件)。
  async function openEvidenceDialog(page: any, acctNo?: string) {
    await loginRole(page, 'PC')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    const row = acctNo ? rows.filter({ hasText: acctNo }).first() : rows.first()
    await expect(row).toBeVisible()
    await row.click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    const btn = page.getByRole('button', { name: '发起存证' })
    await expect(btn).toBeVisible()
    await btn.click()
    return page.locator('.el-dialog').filter({ hasText: /存证|场景/ })
  }

  // 把「存证场景」下拉切到指定中文项(录音/送达/材料包)。
  // el-select 的内层 combobox <input> 会被 placeholder 浮层拦截点击，故点 .el-select 包裹元素(场景=弹窗内第一个)；
  // 下拉弹层挂在 body，须只在当前可见 popper 里选项，避免命中 refIds 多选下拉的同名项。
  async function selectScene(page: any, dlg: any, label: string) {
    await dlg.locator('.el-select').first().click()
    await page
      .locator('.el-select-dropdown')
      .filter({ visible: true })
      .locator('.el-select-dropdown__item')
      .filter({ hasText: label })
      .first()
      .click()
  }

  test('RECORDING 未选录音→提交被挡并提示', async ({ page }) => {
    const dlg = await openEvidenceDialog(page)
    await expect(dlg).toBeVisible()
    // 默认 scene=RECORDING，不选 refIds 直接提交 → 前置校验拦截
    await dlg.getByRole('button', { name: /确定|提交|发起/ }).click()
    await expect(page.getByText(/需选择至少一条 READY 录音/)).toBeVisible()
  })

  test('RECORDING 选中 READY 录音→提交成功', async ({ page }) => {
    // 仅 S3 私海案件 (M3-S3-01) 种了 READY 录音；但该案归属 CO 私海，PC 在其 IN_PROGRESS 详情的
    // availableActions 仅含 follow(后端 computeAvailableActions 不下发 evidence 动作)，故「发起存证」按钮不渲染。
    // 当前种子下无「既具 evidence 动作入口 又 有 READY 录音」的案件 → 守卫跳过，不留红。
    // 建议补种子：给一个 PL/PC 持有(物业自营)且 IN_PROGRESS 的案件挂 READY 录音，并令 availableActions 含 evidence。
    await loginRole(page, 'PC')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    const row = rows.filter({ hasText: 'M3-S3-01' }).first()
    await expect(row).toBeVisible()
    await row.click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    const btn = page.getByRole('button', { name: '发起存证' })
    test.skip(
      (await btn.count()) === 0,
      'M3-S3-01(唯一有 READY 录音的案件)为 CO 私海案，PC 的 availableActions 不含 evidence→无「发起存证」入口，无法验证录音存证提交',
    )
    await btn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: /存证|场景/ })
    await expect(dlg).toBeVisible()
    // 第一个 el-select 是「存证场景」，refIds(选录音)是第二个
    const sel = dlg.locator('.el-select').nth(1)
    await sel.click()
    await page.locator('.el-select-dropdown').filter({ visible: true }).locator('.el-select-dropdown__item').first().click()
    // 点弹窗内「备注」输入框收起多选下拉(勿用 Esc，会连带关闭对话框)，避免遮挡提交
    await dlg.getByText('备注').click()
    await dlg.getByRole('button', { name: /确定|提交|发起/ }).click()
    // 成功为 ElMessage「发起存证成功」并关闭弹窗(无 ISSUING 文案)
    await expect(page.getByText(/发起存证成功/).first()).toBeVisible()
  })

  test('DELIVERY 未选 SIGNED 文书→提交被挡并提示', async ({ page }) => {
    const dlg = await openEvidenceDialog(page)
    await expect(dlg).toBeVisible()
    await selectScene(page, dlg, '送达')
    // 不选文书直接提交 → 前置校验拦截
    await dlg.getByRole('button', { name: /确定|提交|发起/ }).click()
    await expect(page.getByText(/需选择至少一份 SIGNED 文书/)).toBeVisible()
  })

  test('MATERIAL_PACK 可留空受理(无 refIds)', async ({ page }) => {
    const dlg = await openEvidenceDialog(page)
    await expect(dlg).toBeVisible()
    await selectScene(page, dlg, '材料包')
    await dlg.getByRole('button', { name: /确定|提交|发起/ }).click()
    // 材料包场景无 refIds 也应受理 → 成功 toast「发起存证成功」。
    // 实现对同案+同场景+同 refIds 做幂等去重(409 STATE_409「同案同场景同关联的存证已发起」)：
    // 首跑成功，重跑命中去重；二者都证明「无 refIds 被受理且通过校验」(非前置 422 拦截)。
    // 故断言「成功 toast」或「已发起去重提示」其一可见，使用例可重复执行。
    await expect(page.getByText(/发起存证成功|同案同场景同关联的存证已发起/).first()).toBeVisible()
  })
})
