import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M2-01/02 物业负责人创建/编辑项目、维护减免/协调员；BR-M2-11 非负责人无维护入口。
//
// 现状（ds-admin 改版后，src/views/ProjectsView.vue）：
//   列表是原生 table；点行内「查看档案」→ **内联展开**项目全量档案（不再跳 /projects/:id，该路由已无入口）。
//   内联档案里：「编辑档案」开 ProjectEditDialog（DsDrawer, role=dialog，标题 新建项目 / 编辑项目档案，
//   提交按钮 创建项目 / 保存修改）；「减免政策维护」是内联表格 +「保存减免政策」；
//   「+ 管理协调员」开 CoordinatorPicker（DsDrawer + el-transfer +「保存协调员」）。

/** 新建/编辑弹层的必填项：项目名称 + 物业公司 + 省市 + 缴费标准首行 + 收佣比例(%)。 */
async function fillRequiredProjectFields(dlg: any, page: any, name: string) {
  await dlg.getByPlaceholder('如：阳光花园二期').fill(name)
  await dlg.getByPlaceholder('如：成都阳光物业服务有限公司').fill('E2E 物业服务有限公司')

  // 省 → 市：弹层里有 7 个 el-select，按 form-item 标签定位（下拉挂 body，只点当前可见的那个）
  const pickSelect = async (label: string) => {
    await dlg.locator('.el-form-item').filter({ hasText: label }).locator('.el-select').first().click()
    const item = page.locator('.el-select-dropdown').filter({ visible: true }).locator('.el-select-dropdown__item').first()
    await expect(item).toBeVisible()
    await item.click()
    await expect(page.locator('.el-select-dropdown').filter({ visible: true })).toHaveCount(0)
  }
  await pickSelect('省 *')
  await pickSelect('市 *')   // 省选定后才解禁

  // 缴费标准首行（业态 / 收费标准）
  await dlg.getByPlaceholder('住宅 / 商铺 / 车位…').first().fill('住宅')
  await dlg.getByPlaceholder('如：2.5 元/㎡·月').first().fill('2.5 元/㎡·月')

  // 收佣比例(%) el-input-number
  await dlg.locator('.el-input-number input').last().fill('30')
}

const openFirstProfile = async (page: any) => {
  await page.goto('/projects')
  const rows = page.locator('tbody tr')
  await expect(rows.first()).toBeVisible()
  await rows.first().getByText('查看档案').click()
  await expect(page.getByText('减免政策维护（项目级·阶梯）')).toBeVisible()
}

test.describe('US-M2 项目维护(PL)', () => {
  test('PL 新建项目→填基本信息+收佣比例→出现在列表', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '项目管理' }).click()
    await expect(page).toHaveURL(/\/projects/)
    await page.getByRole('button', { name: '+ 新建项目' }).click()

    const dlg = page.getByRole('dialog').filter({ hasText: '新建项目' })
    await expect(dlg).toBeVisible()
    const name = 'E2E测试项目' + Date.now()
    await fillRequiredProjectFields(dlg, page, name)
    await dlg.getByRole('button', { name: '创建项目' }).click()

    await expect(page.getByText('已新建项目')).toBeVisible()
    await expect(page.getByText(name).first()).toBeVisible()
  })

  test('PL 编辑档案改名→列表刷新展示新值', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstProfile(page)
    // 内联档案里的「编辑档案」（列表行内无此按钮）
    await page.getByRole('button', { name: '编辑档案' }).click()

    const dlg = page.getByRole('dialog').filter({ hasText: '编辑项目档案' })
    await expect(dlg).toBeVisible()
    const newName = '翠湖一期-E2E' + (Date.now() % 100000)
    await dlg.getByPlaceholder('如：阳光花园二期').fill(newName)
    // 种子项目建于「物业公司」成为必填之前 → 编辑时须补齐，否则 el-form 校验拦下
    const propCompany = dlg.getByPlaceholder('如：成都阳光物业服务有限公司')
    if (!(await propCompany.inputValue())) await propCompany.fill('翠湖物业服务有限公司')
    await dlg.getByRole('button', { name: '保存修改' }).click()

    await expect(page.getByText('已更新项目档案')).toBeVisible()
    await expect(page.getByText(newName).first()).toBeVisible()
  })

  test('PL 维护减免阶梯：改首档折扣+上限→保存成功(元↔分换算由前端做)', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstProfile(page)

    const tierTable = page.locator('table').filter({ hasText: '减免上限' })
    await expect(tierTable).toBeVisible()
    // 无阶梯行时先加一档（按「折扣」输入框是否存在判断，空态行不含 input）
    const discountInput = tierTable.getByPlaceholder('如 9折')
    if (!(await discountInput.count())) {
      await page.getByRole('button', { name: '+ 增加阶梯' }).click()
    }
    await expect(discountInput.first()).toBeVisible()
    await discountInput.first().fill('9折')
    await tierTable.getByPlaceholder('¥上限（空=不限）').first().fill('500')

    // 保存按钮由 reduceDirty 解禁
    const saveBtn = page.getByRole('button', { name: '保存减免政策' })
    await expect(saveBtn).toBeEnabled()
    await saveBtn.click()
    await expect(page.getByText(/项目级减免政策已保存/)).toBeVisible()
  })

  // 回归保护：「+ 管理协调员」曾只置 coordEditDlg=true 却无对应弹层（死按钮），
  // 导致物业负责人在 UI 上根本挂不了协调员（批次级协调员是平台 batch.import，物业侧唯一入口就在这）。
  test('PL 维护项目协调员：开弹层→选 PC→保存', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstProfile(page)
    await page.getByRole('button', { name: '+ 管理协调员' }).click()

    const dlg = page.getByRole('dialog').filter({ hasText: '维护项目协调员' })
    await expect(dlg).toBeVisible()
    await expect(dlg.locator('.el-transfer')).toBeVisible()
    await expect(dlg.locator('.el-transfer-panel__item').first()).toBeVisible()

    // 全量覆盖语义：重复跑后候选可能已全在右侧「已关联」→ 左侧为空，此时直接保存即可
    const leftItems = dlg.locator('.el-transfer-panel').first().locator('.el-transfer-panel__item')
    const n = await leftItems.count()
    if (n >= 1) await leftItems.nth(0).click()
    if (n >= 1) {
      // nth(1)=左→右(►)；nth(0)=右→左(◄) 右侧无勾选时恒 disabled
      await dlg.locator('.el-transfer__button').nth(1).click().catch(() => {})
    }
    await dlg.getByRole('button', { name: '保存协调员' }).click()
    await expect(page.getByText('已更新项目协调员')).toBeVisible()
  })

  test('CO 无项目入口：无「项目管理」菜单且直敲 /projects 被守卫拦截(BR-M2-11)', async ({ page }) => {
    // 种子现实：项目归属物业组织(翠湖/阳光物业)，CO 属服务商(捷信)组织、不拥有项目。
    // NAV_BY_ROLE.CO 无 projects（案件入口是私海/公海），菜单即路由白名单 → 直敲亦被弹回。
    // 服务端 own-org 裁剪(CO 见空集)仍由 smoke/Gate1 覆盖；此处验 UI 层彻底不可达。
    await loginRole(page, 'CO')
    await expect(page.getByRole('menuitem', { name: '项目管理' })).toHaveCount(0)
    await page.goto('/projects')
    await expect(page).not.toHaveURL(/\/projects/)
    await expect(page.getByRole('button', { name: '编辑档案' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '+ 管理协调员' })).toHaveCount(0)
  })

  test('新建项目必填(项目名称)缺失→提交被阻止并提示', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.goto('/projects')
    await page.getByRole('button', { name: '+ 新建项目' }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '新建项目' })
    await expect(dlg).toBeVisible()

    await dlg.getByRole('button', { name: '创建项目' }).click()
    // el-form 校验拦下：弹层不关，且出现必填提示
    await expect(dlg).toBeVisible()
    await expect(page.getByText('项目名称必填')).toBeVisible()
  })
})
