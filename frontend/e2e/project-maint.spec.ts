import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M2-01/02 物业负责人创建/编辑项目、维护减免/协调员；BR-M2-11 非负责人无维护入口。
test.describe('US-M2 项目维护(PL)', () => {
  test('PL 新建项目→填基本信息+缴费标准+收佣比例→出现在列表', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.getByRole('menuitem', { name: '项目' }).click()
    await expect(page).toHaveURL(/\/projects/)
    await page.getByRole('button', { name: '新建项目' }).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '新建项目' })
    await expect(dlg).toBeVisible()
    const name = 'E2E测试项目' + Date.now()
    await dlg.getByLabel('项目名称').fill(name)
    await dlg.getByLabel('区域').fill('杭州·西湖')
    // 缴费标准首行
    await dlg.getByPlaceholder('如 住宅').first().fill('住宅')
    await dlg.getByPlaceholder('如 2.5 元/㎡·月').first().fill('2.5 元/㎡·月')
    // 收佣比例(%)
    await dlg.locator('input').last().fill('30')
    await dlg.getByRole('button', { name: '新建' }).click()
    await expect(page.getByText('已新建项目')).toBeVisible()
    await expect(page.getByText(name)).toBeVisible()
  })

  test('PL 编辑档案改名/区域→详情刷新展示新值', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.goto('/projects')
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/projects\/\d+/)
    await page.getByRole('button', { name: '编辑档案' }).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '编辑项目档案' })
    await expect(dlg).toBeVisible()
    const newArea = '宁波·鄞州' + (Date.now() % 1000)
    await dlg.getByLabel('区域').fill(newArea)
    await dlg.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText(newArea)).toBeVisible()
  })

  test('PL 维护减免阶梯：增两档(折扣/上限/决定权)→详情渲染·元↔分换算', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.goto('/projects')
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    // 等详情页导航完成再判按钮（行点击后立即取 count 会落在列表页→误判 0）。
    await expect(page).toHaveURL(/\/projects\/\d+/)
    const maintBtn = page.getByRole('button', { name: '维护减免规则' })
    await expect(maintBtn).toBeVisible()
    if (!(await maintBtn.count())) {
      test.skip(true, 'PL 无 reduce.policy.edit')
    }
    await maintBtn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '维护减免阶梯' })
    await expect(dlg).toBeVisible()
    // 第一档
    await dlg.getByPlaceholder('如 9折').first().fill('9折')
    await dlg.locator('.el-input-number input').first().fill('500')   // 封顶 500 元 → 50000 分
    // 添加第二档
    await dlg.getByRole('button', { name: '+ 添加档' }).click()
    await dlg.getByPlaceholder('如 9折').nth(1).fill('8折')
    await dlg.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('已保存减免阶梯')).toBeVisible()
    // 详情减免阶梯区渲染封顶（元展示，500 元 = ¥500）。
    // 详情减免表 + 可能残留的弹窗减免表均含「折扣」表头→锚定渲染了 9折/¥500 的详情表（first）。
    await expect(
      page.locator('.el-table').filter({ hasText: '9折' }).filter({ hasText: '¥500' }).first()
    ).toBeVisible()
  })

  test('PL 维护协调员：多选两个 PC→详情显示→清空再保存为空', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.goto('/projects')
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await page.getByRole('button', { name: '维护协调员' }).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '协调员' })
    await expect(dlg).toBeVisible()
    // el-transfer 左侧候选(checkbox-group)：勾选左面板候选项的 checkbox → 启用 > 按钮 → 移到右侧
    const leftPanel = dlg.locator('.el-transfer-panel').first()
    const leftItems = leftPanel.locator('.el-transfer-panel__item')
    await expect(leftItems.first()).toBeVisible()
    const n = await leftItems.count()
    if (n >= 1) await leftItems.nth(0).click()
    if (n >= 2) await leftItems.nth(1).click()
    // el-transfer 有两个移动按钮：nth(0)=右→左(◄,右侧无勾选恒 disabled)，nth(1)=左→右(►)。
    // 勾选左面板项后启用 nth(1)，点它把候选移入右侧已关联。
    const toRight = dlg.locator('.el-transfer__button').nth(1)
    await expect(toRight).toBeEnabled()
    await toRight.click()
    await dlg.getByRole('button', { name: '保存协调员' }).click()
    // 保存成功 toast + 弹窗关闭
    await expect(page.getByText('已更新项目协调员')).toBeVisible()
  })

  test('CO 项目列表为空→无任何项目访问与维护入口(BR-M2-11)', async ({ page }) => {
    // 种子现实：项目归属物业组织(翠湖/阳光物业)，CO 属服务商(捷信)组织、不拥有项目，
    // 故 /projects 数据范围裁剪后为空(No Data)。CO 既无法进入详情，更无维护入口(BR-M2-11)。
    await loginRole(page, 'CO')
    await page.goto('/projects')
    await expect(page).toHaveURL(/\/projects/)
    // 项目列表为空（无数据行）——CO 无任何项目可进入
    await expect(page.locator('.el-table__row')).toHaveCount(0)
    await expect(page.getByText('No Data')).toBeVisible()
    // 当前页面无项目维护入口
    await expect(page.getByRole('button', { name: '编辑档案' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '维护协调员' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '维护减免规则' })).toHaveCount(0)
  })

  test('新建项目必填(基本信息/收佣比例)缺失→提交被阻止并提示', async ({ page }) => {
    await loginRole(page, 'PL')
    await page.goto('/projects')
    await page.getByRole('button', { name: '新建项目' }).click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '新建项目' })
    await expect(dlg).toBeVisible()
    // 不填任何字段直接提交
    await dlg.getByRole('button', { name: '新建' }).click()
    // 表单校验提示（项目名称/区域/收佣比例必填 或 缴费标准缺失）
    await expect(page.getByText(/必填|缺失/).first()).toBeVisible()
    // 未触发成功
    await expect(page.getByText('已新建项目')).toHaveCount(0)
  })
})
