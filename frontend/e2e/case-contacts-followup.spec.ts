import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// US-M4 联系人管理与跟进留痕：
// 联系人表显主号标记并可设主号(PATCH isPrimary，旧主号降级)；新增联系人可勾主号；
// 写跟进可上传附件并在时间线留痕。
test.describe('US-M4 联系人与跟进(CO)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'CO')
    await page.getByRole('menuitem', { name: '案件' }).click()
    await expect(page).toHaveURL(/\/cases/)
    const rows = page.locator('.el-table__row')
    await expect(rows.first()).toBeVisible()
    await rows.first().click()
    await expect(page).toHaveURL(/\/cases\/\d+/)
    // 默认在「概览 / 联系人」tab
    await page.getByRole('tab', { name: /概览 \/ 联系人/ }).click()
  })

  test('联系人表显主号标记且可设主号', async ({ page }) => {
    const table = page.locator('.el-table').first()
    await expect(table).toBeVisible()
    // 主号列存在
    await expect(page.getByText('主号').first()).toBeVisible()
    const setBtn = page.getByRole('button', { name: '设主号' })
    if (await setBtn.count()) {
      await setBtn.first().click()
      await expect(page.getByText('已设为主号')).toBeVisible()
    }
  })

  test('新增联系人对话框可勾主号', async ({ page }) => {
    const addBtn = page.getByRole('button', { name: /新增联系人|添加联系人|\+ ?联系人/ })
    if (!(await addBtn.count())) {
      test.skip(true, '无新增联系人入口(权限/视图)')
    }
    await addBtn.first().click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '新增联系人' })
    await expect(dlg).toBeVisible()
    await dlg.getByPlaceholder('联系号码').fill('13900008888')
    // 设为主号开关
    await dlg.getByText('设为主号').click()
    await dlg.getByRole('button', { name: '提交' }).click()
    await expect(page.getByText('已新增联系人')).toBeVisible()
  })

  test('写跟进可上传附件并在时间线留痕', async ({ page }) => {
    const followBtn = page.getByRole('button', { name: '写跟进' })
    if (!(await followBtn.count())) {
      test.skip(true, '无写跟进入口(case.follow)')
    }
    await followBtn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '写跟进' })
    await expect(dlg).toBeVisible()
    // 内容必填(后端校验)，否则提交 422、弹窗不关
    await dlg.getByRole('textbox', { name: '内容' }).fill('e2e 跟进留痕')
    // 附件加行入口，填名称+url(空行会被前端过滤掉)
    await expect(dlg.getByRole('button', { name: '+ 加附件' })).toBeVisible()
    await dlg.getByRole('button', { name: '+ 加附件' }).click()
    await dlg.getByPlaceholder('名称').fill('录音')
    await dlg.getByPlaceholder('url').fill('https://example.com/a.mp3')
    await dlg.getByRole('button', { name: '提交' }).click()
    // 提交成功后弹窗关闭
    await expect(dlg).toBeHidden()
    // 切到时间线留痕。el-tabs 非激活 tab 的面板仍留 DOM(hidden)，故须在「事件时间线」激活面板内断言，
    // 否则 .el-table.first() 会命中其他 tab 的隐藏表格。该面板渲染 el-timeline，并能见到刚写的跟进留痕。
    await page.getByRole('tab', { name: '事件时间线' }).click()
    const tlPanel = page.getByRole('tabpanel', { name: '事件时间线' })
    await expect(tlPanel.locator('.el-timeline')).toBeVisible()
    await expect(tlPanel.getByText('e2e 跟进留痕').first()).toBeVisible()
  })
})
