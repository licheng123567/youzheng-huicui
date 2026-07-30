import { test, expect } from '@playwright/test'
import { allowedPaths, navLabel } from '../src/constants/nav'
import { switchRole, type RoleKey } from './helpers'
import { installDiagnostics } from './fixtures/diagnostics'

const ROLES = ['SA', 'SE', 'PL', 'PC', 'VL', 'CO'] as const satisfies readonly RoleKey[]
const ALL_PATHS = [...new Set(ROLES.flatMap((role) => allowedPaths(role)))]

for (const role of ROLES) {
  for (const path of allowedPaths(role)) {
    test(`${role} opens ${path} from its visible menu`, async ({ page }, testInfo) => {
      await switchRole(page, role)
      const diagnostic = installDiagnostics(page, testInfo)
      const item = page.getByRole('menuitem', {
        name: navLabel(path, role),
        exact: true,
      })
      await expect(item).toBeVisible()
      await item.click()
      const escapedPath = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      await expect(page).toHaveURL(new RegExp(`${escapedPath}(?:$|[/?#])`))
      await expect(page.locator('main.body > *').first()).toBeVisible()
      await page.waitForTimeout(750)
      await diagnostic.assertClean()
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
}
