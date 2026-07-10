import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// 催收员 App 下载入口（顶栏「App」）对所有角色可见，但**文案按角色分**：
//   催收员(CO) → 「用手机扫码安装」，是给他自己装的；
//   其它角色    → 「App 只对催收员开放」，链接是给他转发给手下催收员的。
//
// 这条分叉是本功能唯一会做错的地方（BR-APP-01：App 仅 CO 角色开放）。
// 装完发现进不去，比没有入口更糟，所以两条分支都要钉住。
test.describe('顶栏 App 下载入口', () => {
  test('催收员：看到安装指引 + 二维码', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.locator('.top .link', { hasText: /^App$/ }).click()

    await expect(page.getByText('催收员 App（Android）')).toBeVisible()
    await expect(page.getByText('用手机扫码安装，然后用你现在这个账号登录。')).toBeVisible()
    // 二维码是 QRCode.toDataURL 生成的 data: 图
    await expect(page.locator('img[alt="App 下载二维码"]')).toBeVisible()
    await expect(page.locator('img[alt="App 下载二维码"]')).toHaveAttribute('src', /^data:image\/png/)
  })

  test('物业负责人：看到「仅催收员可用」的转发提示，而不是安装指引', async ({ page }) => {
    await loginAs(page, 'cuihu_pl')
    await page.locator('.top .link', { hasText: /^App$/ }).click()

    await expect(page.getByText(/App 只对.*催收员.*开放/)).toBeVisible()
    await expect(page.getByText('用手机扫码安装，然后用你现在这个账号登录。')).toHaveCount(0)
  })

  test('平台超管：入口同样可见（管理角色也要能拿到链接分发）', async ({ page }) => {
    await loginAs(page, 'admin')
    await expect(page.locator('.top .link', { hasText: /^App$/ })).toBeVisible()
  })

  test('使用说明覆盖录音链路的三条硬边界', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.locator('.top .link', { hasText: /^App$/ }).click()

    // 用户最容易踩的三个坑：以为 App 自己录音、以为 iOS 也行、以为不用开系统录音
    await expect(page.getByText(/只有 Android/)).toBeVisible()
    await expect(page.getByText(/录音是你手机的系统功能录的，不是 App 录的/)).toBeVisible()
    await expect(page.getByText(/这是唯一能证明整条链路通了的检验/)).toBeVisible()
  })
})
