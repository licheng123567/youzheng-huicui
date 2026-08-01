import { test, expect } from './fixtures/test'
import { allowedPaths, isAllowedPath, navLabel } from '../src/constants/nav'
import { switchRole, type RoleKey } from './helpers'

const ROLES = ['SA', 'SE', 'PL', 'PC', 'VL', 'CO'] as const satisfies readonly RoleKey[]
const ALL_PATHS = [...new Set(ROLES.flatMap((role) => allowedPaths(role)))]

for (const role of ROLES) {
  for (const path of allowedPaths(role)) {
    test(`${role} opens ${path} from its visible menu`, async ({ page }) => {
      await switchRole(page, role)
      const item = page.getByRole('menuitem', {
        name: navLabel(path, role),
        exact: true,
      })
      await expect(item).toBeVisible()
      await item.click()
      const escapedPath = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      await expect(page).toHaveURL(new RegExp(`${escapedPath}(?:$|[/?#])`))
      await expect(page.locator('main.body > *').first()).toBeVisible()
      await page.waitForLoadState('networkidle')
    })
  }

  test(`${role} hides forbidden menu entries`, async ({ page }) => {
    await switchRole(page, role)
    const allowedLabels = new Set(allowedPaths(role).map((path) => navLabel(path, role)))
    const forbiddenLabels = [
      ...new Set(
        ALL_PATHS.map((path) => navLabel(path, role)).filter(
          (label) => !allowedLabels.has(label),
        ),
      ),
    ]
    for (const label of forbiddenLabels) {
      await expect(page.getByRole('menuitem', { name: label, exact: true })).toHaveCount(0)
    }
  })

  test(`${role} rejects every forbidden direct menu route`, async ({ page }) => {
    await switchRole(page, role)
    const universalPaths = ['/dashboard', '/profile', '/search', '/notifications']
    const forbiddenPaths = ALL_PATHS.filter(
      (path) => !isAllowedPath(role, path, universalPaths),
    )
    for (const path of forbiddenPaths) {
      await test.step(path, async () => {
        await page.goto(path)
        await expect(page).toHaveURL(/\/dashboard$/)
        await page.waitForLoadState('networkidle')
      })
    }
  })
}
