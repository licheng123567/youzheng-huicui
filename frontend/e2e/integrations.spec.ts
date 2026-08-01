import { test, expect } from './fixtures/test'
import { loginRole } from './helpers'

// v1.23.0 参数配置页三件事（用户原话）：
//   ①「系统配置·业务规则这些配置内容过于开发向，需要中文显示说明」→ 此前把 JSON 原样 dump（{"holdCap":50}）
//   ②「AI 配置逻辑也是一样」→ 同上，且必须说清哪些其实**没接入**（LLM/ASR 客户端 Phase 3 才写）
//   ③「易保全、LLM、ASR 这些三方端口的 key，后台没有配置界面」→ 只给真接得通的两个通道做（易保全+短信）
test.describe('v1.23.0 参数配置人话化 + 三方通道（v1.24.0 含 AI 密钥）', () => {

  test('SA 平台超管：业务规则显示中文名与说明，不再 dump JSON', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.getByRole('menuitem', { name: '参数配置' }).click()
    await expect(page).toHaveURL(/\/settings/)

    // 域名与参数都是中文，且带一句话说明
    await expect(page.getByText('时效参数').first()).toBeVisible()
    await expect(page.getByText('单人持有上限').first()).toBeVisible()
    await expect(page.getByText(/一个催收员同时最多能持有多少案件/)).toBeVisible()

    // 旧的裸 JSON 不再出现（这是本次要消灭的东西）
    await expect(page.getByText('{"holdCap":50}')).toHaveCount(0)
  })

  // v1.24.0：引擎已实现（百炼 ASR + DeepSeek LLM 真接入），「生效与否」不再写死，
  // 而是取决于三方通道里有没有配 key 并启用——页面必须如实反映，不能再说「尚未接入」。
  test('SA 平台超管：AI 配置分组显示，生效状态随密钥配置而变', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/settings')

    await expect(page.getByText('话术飞轮').first()).toBeVisible()
    await expect(page.getByText('变体晋升条件').first()).toBeVisible()
    await expect(page.getByText('大模型 LLM').first()).toBeVisible()
    await expect(page.getByText('语音转写 ASR').first()).toBeVisible()

    // 未配 key（种子态）→ 提示去【三方通道】填；且绝不说「引擎不存在」
    const llmRow = page.locator('tbody tr')
      .filter({ has: page.getByRole('button', { name: '配置' }) })
      .filter({ hasText: 'DeepSeek' })
    const llmConfigured = await llmRow.getByText('已启用').count() > 0
    if (llmConfigured) {
      await expect(page.getByText(/AI 已接入并生效/)).toBeVisible()
    } else {
      await expect(page.getByText(/尚未配置密钥/)).toBeVisible()
      await expect(page.getByText(/录音停在「解析中」/).first()).toBeVisible()
    }
  })

  test('SA 平台超管：三方通道含百炼与 DeepSeek（AI 密钥可后台配置）', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/settings')
    const igRows = page.locator('tbody tr').filter({ has: page.getByRole('button', { name: '配置' }) })
    await expect(igRows.filter({ hasText: '阿里百炼' })).toBeVisible()
    await expect(igRows.filter({ hasText: 'DeepSeek' })).toBeVisible()
    // 说明必须讲清「未配置时降级、不报错」——否则运维会以为不配 key 就传不了录音
    await expect(page.getByText(/未配置时维持占位行为/)).toBeVisible()
  })

  test('SA 平台超管：三方通道可配置，密钥只回显后四位', async ({ page }) => {
    await loginRole(page, 'SA')
    await page.goto('/settings')

    await expect(page.getByText('三方通道').first()).toBeVisible()
    const row = page.locator('tbody tr').filter({ hasText: '易保全' }).first()
    await expect(row).toBeVisible()

    await row.getByRole('button', { name: '配置' }).click()
    const dlg = page.getByRole('dialog')
    await expect(dlg).toContainText('留空 = 保持不变')

    // 填 key 并启用 → 保存
    await dlg.locator('.el-form-item:has(label:text-is("接口地址"))').locator('input').fill('https://bs.ebaoquan.org')
    await dlg.locator('.el-form-item:has(label:text-is("appKey 密钥"))').locator('input').fill('E2E_SECRET_9876')
    await dlg.locator('.el-form-item:has(label:text-is("appKey"))').locator('input').fill('E2E_APPKEY_1234')
    const sw = dlg.locator('.el-switch')
    if ((await sw.getAttribute('class'))?.includes('is-checked') !== true) await sw.click()
    await dlg.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText(/已保存/)).toBeVisible()

    // 列表回显：只见后四位，明文永不出接口
    const after = page.locator('tbody tr').filter({ hasText: '易保全' }).first()
    await expect(after).toContainText('****1234')
    await expect(after).toContainText('****9876')
    await expect(after).not.toContainText('E2E_APPKEY_1234')
    await expect(after).toContainText('后台维护')      // source=DB

    // 还原（关掉通道 + 清除密钥），让 spec 可重复跑
    await after.getByRole('button', { name: '配置' }).click()
    const d2 = page.getByRole('dialog')
    await d2.locator('.el-switch').click()                     // 关掉启用
    await d2.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText(/已保存/)).toBeVisible()
  })

  test('PL 物业负责人：无参数配置菜单（平台专属）', async ({ page }) => {
    await loginRole(page, 'PL')
    await expect(page.getByRole('menuitem', { name: '参数配置' })).toHaveCount(0)
  })
})
