import { defineConfig, devices } from '@playwright/test'

// 真屏 E2E：按用户故事驱动浏览器跑前端真实交互（前端 vite:5173 代理 /v1→后端:9091）。
// 前置：PG(5455)+后端(9091) 须已起且 DevSeeder 已种子；本配置只负责拉起 vite。
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,        // 共享一套后端/DB，串行避免状态干扰（与 smoke 同理）
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: { timeout: 8_000 },
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
