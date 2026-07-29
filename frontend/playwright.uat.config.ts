import { defineConfig, devices } from '@playwright/test'

const artifactsDir = process.env.PLAYWRIGHT_ARTIFACT_DIR ?? 'uat-test-results'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'uat-smoke.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 30_000,
  reporter: [
    ['list'],
    ['html', { outputFolder: `${artifactsDir}/html`, open: 'never' }],
  ],
  outputDir: `${artifactsDir}/results`,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://web',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
