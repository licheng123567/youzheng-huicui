import {
  test as base,
  expect,
  devices,
  type Page,
} from '@playwright/test'
import {
  installDiagnostics,
  type AllowedHttpFailure,
} from './diagnostics'

type DiagnosticFixtures = {
  allowHttpFailure: (rule: AllowedHttpFailure) => void
  _allowedHttpFailures: AllowedHttpFailure[]
  _automaticDiagnostics: void
}

export const test = base.extend<DiagnosticFixtures>({
  _allowedHttpFailures: async ({}, use) => {
    await use([])
  },

  allowHttpFailure: async ({ _allowedHttpFailures }, use) => {
    await use((rule) => _allowedHttpFailures.push(rule))
  },

  _automaticDiagnostics: [
    async ({ page, _allowedHttpFailures }, use, testInfo) => {
      const diagnostic = installDiagnostics(page, testInfo, _allowedHttpFailures)
      await use()

      await diagnostic.waitForQuiescence()
      const testPassed = testInfo.status === testInfo.expectedStatus
      await diagnostic.assertClean(testPassed)
    },
    { auto: true },
  ],
})

export { expect, devices }
export type { Page }
