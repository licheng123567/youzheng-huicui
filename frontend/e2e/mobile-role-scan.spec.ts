import { test, expect, devices } from '@playwright/test'
import { switchRole } from './helpers'
import { installDiagnostics } from './fixtures/diagnostics'

const { defaultBrowserType: _defaultBrowserType, ...iphone } = devices['iPhone 13']
test.use({ ...iphone })

for (const role of ['PC', 'CO'] as const) {
  test(`${role} mobile shell and case detail stay usable`, async ({ page }, testInfo) => {
    await switchRole(page, role)
    await page.waitForLoadState('networkidle')
    const diagnostic = installDiagnostics(page, testInfo)

    for (const path of ['/m', '/m/cases', '/m/calls', '/m/me']) {
      await page.goto(path)
      await page.waitForLoadState('networkidle')
      await expect(page.locator('.m-app')).toBeVisible()
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - window.innerWidth,
      )
      expect(overflow).toBeLessThanOrEqual(1)
    }

    await page.goto('/m/cases')
    await page.waitForLoadState('networkidle')
    const card = page.locator('.m-body .mc').first()
    await expect(card).toBeVisible()
    await card.click()
    await expect(page).toHaveURL(/\/m\/cases\/\d+/)
    await diagnostic.assertClean()
  })
}

test('SA is redirected away from the mobile worker shell', async ({ page }) => {
  await switchRole(page, 'SA')
  await page.goto('/m')
  await expect(page).toHaveURL(/\/dashboard$/)
})
