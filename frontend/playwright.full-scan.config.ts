import { defineConfig, devices } from '@playwright/test'

const artifacts = process.env.PLAYWRIGHT_ARTIFACT_DIR ?? 'full-scan-results'
const { defaultBrowserType: _defaultBrowserType, ...iphone13 } = devices['iPhone 13']

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  reporter: [
    ['list'],
    ['json', { outputFile: `${artifacts}/results.json` }],
    ['html', { outputFolder: `${artifacts}/html`, open: 'never' }],
  ],
  outputDir: `${artifacts}/artifacts`,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://web',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'desktop-chromium',
      testIgnore: /mobile-role-scan\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'mobile-chromium',
      testMatch: /mobile-role-scan\.spec\.ts/,
      use: { ...iphone13 },
    },
  ],
})
