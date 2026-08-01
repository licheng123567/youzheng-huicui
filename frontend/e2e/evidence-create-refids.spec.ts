import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// BR-M6 按次存证：UI 入口与 evidence.create 门控。
//
// 口径变更（送达存证区重构后，见 CaseThreeColumn / AiReviewPanel）：
//   已**没有**独立的「发起存证」对话框（旧版让用户选 scene + 勾 refIds）。现在两个真实入口：
//     ① RECORDING：AI 复盘面板里「🔒 存证本次录音」——scene/refIds 由前端按当前录音固定填入，
//        用户不再手选，故「未选录音→被挡」这类校验在 UI 上已不可达（服务端仍强校验，
//        由 smoke「PL 发起存证(RECORDING)」+ Gate1 覆盖）。
//     ② DELIVERY：案件详情「📎 上传文件 / 凭证（可选存证）」对话框里勾「同时上链存证」。
//   本 spec 因此改为验证：两个入口的存在性与 evidence.create 权限门控。
//
// 定位案件：走全局搜索按户号（/search 通用页）。PC 是管理角色，「案件管理」是**批次**优先列表，
//   点行进的是批次详情，拿不到案件详情。M3-S3-01 = 唯一种了 READY 录音的私海案件。

const ACCT = 'M3-S3-01'

// 角色选择用 PL（物业负责人）而非 PC：
//   pristine 种子里 cuihu_pc.permissions 为 NULL（= 角色全集，含 evidence.create），
//   但 members.spec 的「编辑成员·权限子集」用例会在运行期把 cuihu_pc 改成子集
//   ['case.follow','case.paylink']。于是任何对 PC 的 evidence.create 断言都**依赖 spec 执行顺序**。
//   PL 不被任何用例改权限，是稳定锚点。子集授权本身由 members.spec 自己覆盖。
async function openCase(page: any, role: 'PL' | 'CO') {
  await loginRole(page, role)
  await page.goto(`/search?q=${ACCT}`)
  const rows = page.locator('tbody tr.row-click')
  await expect(rows.first()).toBeVisible()
  await rows.first().click()
  await expect(page).toHaveURL(/\/cases\/\d+/)
}

test.describe('BR-M6 按次存证·UI 入口与门控', () => {
  test('PL(evidence.create) 上传对话框含「同时上链存证」勾选(DELIVERY 入口)', async ({ page }) => {
    await openCase(page, 'PL')
    await page.getByRole('button', { name: /上传文件 \/ 凭证/ }).click()
    const dlg = page.getByRole('dialog').filter({ hasText: '上传文件 / 凭证' })
    await expect(dlg).toBeVisible()
    // 说明文案里也提到「同时上链存证」→ 必须锚定勾选项自身的 label（含 checkbox）
    const evidenceLabel = dlg.locator('label').filter({ hasText: '同时上链存证' })
    await expect(evidenceLabel).toBeVisible()
    await expect(evidenceLabel.locator('input[type="checkbox"]')).toBeVisible()
  })

  test('PL AI 复盘面板含「存证本次录音」(RECORDING 入口)', async ({ page }) => {
    await openCase(page, 'PL')
    const openReview = page.getByRole('button', { name: /查看并标注（AI 复盘）/ })
    if (!(await openReview.count())) {
      test.skip(true, '该案无 READY 录音，AI 复盘入口不渲染')
    }
    await openReview.click()
    const panel = page.getByRole('dialog', { name: 'AI 复盘 · 本次录音' })
    await expect(panel).toBeVisible()
    await expect(panel.getByRole('button', { name: /存证本次录音/ })).toBeVisible()
  })

  // 「上传与存证」整区由 v-if="isPropertyRole" 控制 → 催收员侧根本不渲染该入口
  // （存证是物业/平台的动作，BR-M6 三方隔离：服务商不可见存证）。
  test('CO 无「上传与存证」区(无上传/存证入口)', async ({ page }) => {
    await openCase(page, 'CO')
    await expect(page.getByText('上传与存证', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /上传文件 \/ 凭证/ })).toHaveCount(0)
  })
})
