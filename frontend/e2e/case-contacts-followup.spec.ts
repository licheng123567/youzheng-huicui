import { test, expect } from '@playwright/test'
import { loginRole, openCaseByAcctNo } from './helpers'

// US-M4 联系人管理与跟进留痕：
// 联系人表显主号标记并可设主号(PATCH isPrimary，旧主号降级)；新增联系人可勾主号；
// 写跟进可上传附件并在时间线留痕。
test.describe('US-M4 联系人与跟进(CO)', () => {
  test.beforeEach(async ({ page }) => {
    await loginRole(page, 'CO')
        // CO 的「我的案件」扁平表无户号列 → 走全局搜索按户号定位
    await openCaseByAcctNo(page, 'M3-S3-01')
  })

  test('联系人表显主号标记且可设主号', async ({ page }) => {
    const table = page.locator('table').first()
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
    // 联系方式分区的新增入口按钮文案为「+ 新增」(CaseDetailView)，gated by case.follow。
    // CO 恒有 case.follow → 该入口必然出现；用 toBeVisible 显式等待，
    // 不能用 count()（它不自动等待，页面未渲染完就返回 0 → 用例会"静默跳过"，看着像绿其实没测）。
    const addBtn = page.getByRole('button', { name: '+ 新增' }).first()
    await expect(addBtn).toBeVisible()
    await addBtn.click()
    const dlg = page.getByRole('dialog').filter({ hasText: '新增联系人' })
    await expect(dlg).toBeVisible()
    await dlg.getByPlaceholder('联系号码').fill('13900008888')
    // 设为主号开关
    await dlg.getByText('设为主号').click()
    await dlg.getByRole('button', { name: '提交' }).click()
    await expect(page.getByText('已新增联系人')).toBeVisible()
  })

  test('写跟进可上传附件并在时间线留痕', async ({ page }) => {
    // 同理：CO 恒有 case.follow，「写跟进记录」必然出现 → 显式等待而非 count() 静默跳过
    const followBtn = page.getByRole('button', { name: '写跟进' })
    await expect(followBtn).toBeVisible()
    await followBtn.click()
    const dlg = page.getByRole('dialog').filter({ hasText: '写跟进记录' })
    await expect(dlg).toBeVisible()
    // 内容必填(后端校验)。是裸 <textarea class="ta">（无 label 关联 → 无可访问名），按 placeholder 定位
    await dlg.getByPlaceholder(/记录本次跟进情况/).fill('e2e 跟进留痕')
    // 附件加行入口（文案是「+ 加外链附件」），填名称+url(空行会被前端过滤掉)
    await expect(dlg.getByRole('button', { name: '+ 加外链附件' })).toBeVisible()
    await dlg.getByRole('button', { name: '+ 加外链附件' }).click()
    await dlg.getByPlaceholder('名称').fill('录音')
    await dlg.getByPlaceholder('url').fill('https://example.com/a.mp3')
    await dlg.getByRole('button', { name: '提交' }).click()
    // 提交成功后弹窗关闭
    await expect(dlg).toBeHidden()
    // 切到「沟通记录」看留痕。中栏 tab 是 .dtabs .t 普通 div（非 el-tabs/role=tabpanel），
    // 切换即整块替换内容，故直接在页面上断言刚写的跟进文案。
    await page.locator('.dtabs .t').filter({ hasText: '沟通记录' }).click()
    await expect(page.getByText('e2e 跟进留痕').first()).toBeVisible()
  })
})
