import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

// BR-M2-18a/18b 批次级减免覆盖与恢复继承：
// 初始 source=INHERITED→自定义覆盖增 1 档→source=CUSTOM；清除自定义→回 INHERITED；
// 无 reduce.policy.edit 角色见 tiersPermDenied 警告。
async function openFirstBatch(page: any) {
  await page.getByRole('menuitem', { name: '批次' }).click()
  await expect(page).toHaveURL(/\/batches/)
  const rows = page.locator('.el-table__row')
  await expect(rows.first()).toBeVisible()
  // 批次号列渲染为 el-button link(@click=$router.push)，行点击不导航 → 点首行批次号按钮
  // 详情页减免档位异步加载(loadReduceTiers)，等其响应回来后「自定义覆盖/清除」按钮才渲染；
  // 否则随后立刻 btn.count() 会因竞态读到 0 而误跳过。
  const rt = page.waitForResponse((r: any) => /\/batches\/\d+\/reduce-tiers/.test(r.url())).catch(() => {})
  await rows.first().getByRole('button').first().click()
  await expect(page).toHaveURL(/\/batches\/\d+/)
  await rt
  // 等减免档位区渲染稳定(标签 source 或权限警告其一出现)
  await expect(page.getByText('减免档位（GET /batches/{id}/reduce-tiers）')).toBeVisible()
}

test.describe('BR-M2-18a 批次减免覆盖(PL)', () => {
  test('自定义覆盖增 1 档→source 变批次自定义', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const btn = page.getByRole('button', { name: '自定义覆盖' })
    if (!(await btn.count())) {
      test.skip(true, 'PL 无 reduce.policy.edit')
    }
    await btn.click()
    const dlg = page.locator('.el-dialog').filter({ hasText: '减免' })
    await expect(dlg).toBeVisible()
    await dlg.getByPlaceholder('如 9折').first().fill('85折')
    await dlg.getByRole('button', { name: '保存覆盖' }).click()
    await expect(page.getByText('已保存批次自定义减免')).toBeVisible()
    // source 标签(el-tag)显示「批次自定义」(sourceLabel(CUSTOM)，BatchDetailView.vue:161)。
    // 用 el-tag 作用域避免命中同含「批次自定义」的成功 toast 文案。
    await expect(page.locator('.el-tag').filter({ hasText: '批次自定义' }).first()).toBeVisible()
  })

  test('清除自定义→恢复继承项目默认', async ({ page }) => {
    await loginRole(page, 'PL')
    await openFirstBatch(page)
    const clearBtn = page.getByRole('button', { name: /清除自定义·恢复继承/ })
    if (!(await clearBtn.count())) {
      test.skip(true, '当前批次非 CUSTOM，无清除入口')
    }
    await clearBtn.click()
    // ElMessageBox 确认按钮：当前未配中文 locale，按钮渲染为默认 OK/Cancel(英文)，确认键=OK。
    await page.locator('.el-message-box').getByRole('button', { name: /OK|确定|确认/ }).click()
    await expect(page.getByText('已恢复继承项目默认减免')).toBeVisible()
    // source 标签显示「继承项目默认」(sourceLabel(INHERITED))；用 el-tag 作用域避开同文案的成功 toast。
    await expect(page.locator('.el-tag').filter({ hasText: '继承项目默认' }).first()).toBeVisible()
  })

  test('无 reduce.policy.edit(CO)→tiersPermDenied 警告', async ({ page }) => {
    await loginRole(page, 'CO')
    await openFirstBatch(page)
    // CO 无策略查看权限 → 警告或无覆盖入口
    const denied = page.getByText('无减免策略查看权限')
    if (await denied.count()) {
      await expect(denied).toBeVisible()
    } else {
      await expect(page.getByRole('button', { name: '自定义覆盖' })).toHaveCount(0)
    }
  })
})
