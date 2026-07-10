import { test, expect } from '@playwright/test'
import { loginAs } from './helpers'

// 侧栏「工具 › 外勤作业 App」对所有角色开放，但**页面文案按角色分**：
//   催收员(CO) / 物业协调员(PC) → 「用手机扫码安装」，是给他自己装的；
//   其它四个角色                → 「只对催收员与物业协调员开放」，链接是给他转发的。
//
// 这条分叉是本功能唯一会做错的地方（BR-APP-01：App 仅 CO + PC）。
// 特别注意物业负责人(PL)：他**有 case.call 权限**，但是管理岗，不该装 App。
// 装完发现进不去，比没有入口更糟，所以每条分支都要钉住。
test.describe('菜单 · 外勤作业 App 下载页', () => {
  test('催收员：菜单可进，看到安装指引 + 二维码', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.getByRole('menuitem', { name: '外勤作业 App' }).click()
    await expect(page).toHaveURL(/\/app-download/)

    await expect(page.getByText('用手机扫码安装，然后用你现在这个账号登录。')).toBeVisible()
    // 二维码是 QRCode.toDataURL 生成的 data: 图
    const qr = page.locator('img[alt="App 下载二维码"]')
    await expect(qr).toBeVisible()
    await expect(qr).toHaveAttribute('src', /^data:image\/png/)
  })

  test('物业协调员：也是外勤作业角色，看到安装指引', async ({ page }) => {
    await loginAs(page, 'cuihu_pc')
    await page.getByRole('menuitem', { name: '外勤作业 App' }).click()
    await expect(page).toHaveURL(/\/app-download/)

    await expect(page.getByText('用手机扫码安装，然后用你现在这个账号登录。')).toBeVisible()
    await expect(page.locator('img[alt="App 下载二维码"]')).toBeVisible()
  })

  // PL 有 case.call 权限（BR-M4-01a 允许他给关联案件打电话），但他是管理岗。
  // 若哪天有人把门控从「按角色」改成「按 permissions」，这条会立刻挂。
  test('物业负责人：虽有 case.call 权限，仍看到转发提示而非安装指引', async ({ page }) => {
    await loginAs(page, 'cuihu_pl')
    await page.getByRole('menuitem', { name: '外勤作业 App' }).click()
    await expect(page).toHaveURL(/\/app-download/)

    await expect(page.getByText(/App 只对.*催收员.*物业协调员.*开放/)).toBeVisible()
    await expect(page.getByText('用手机扫码安装，然后用你现在这个账号登录。')).toHaveCount(0)
  })

  test('服务商负责人：转发提示', async ({ page }) => {
    await loginAs(page, 'jx_vl')
    await page.getByRole('menuitem', { name: '外勤作业 App' }).click()
    await expect(page.getByText(/App 只对.*催收员.*物业协调员.*开放/)).toBeVisible()
  })

  test('平台超管：菜单项可见（管理角色也要能拿到链接分发）', async ({ page }) => {
    await loginAs(page, 'admin')
    await expect(page.getByRole('menuitem', { name: '外勤作业 App' })).toBeVisible()
  })

  // 该页进了 nav.ts 六角色菜单 → allowedPaths 自动放行。
  // 若哪天有人从某个角色的 nav 里删掉它，直敲 URL 会被守卫弹回 /dashboard —— 这条会挂。
  test('直敲 URL 不被越权守卫弹回（六角色都在菜单里）', async ({ page }) => {
    await loginAs(page, 'jx_vl')
    await page.goto('/app-download')
    await expect(page).toHaveURL(/\/app-download/)
    await expect(page.getByText('外勤作业 App（Android）')).toBeVisible()
  })

  test('使用说明覆盖录音链路的三条硬边界', async ({ page }) => {
    await loginAs(page, 'jx_co1')
    await page.goto('/app-download')

    // 用户最容易踩的三个坑：以为 App 自己录音、以为 iOS 也行、以为不用开系统录音
    await expect(page.getByText(/只有 Android/)).toBeVisible()
    await expect(page.getByText(/录音是你手机的系统功能录的，不是 App 录的/)).toBeVisible()
    await expect(page.getByText(/这是唯一能证明整条链路通了的检验/)).toBeVisible()
  })
})
