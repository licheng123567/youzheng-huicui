import { defineConfig, devices } from '@playwright/test'

const externalBaseURL = process.env.PLAYWRIGHT_BASE_URL
const localBaseURL = 'http://localhost:5173'

// 真屏 E2E：按用户故事驱动浏览器跑前端真实交互（前端 vite:5173 代理 /v1→后端:9091）。
// 默认本地模式仍拉起 Vite；显式 PLAYWRIGHT_BASE_URL 时复用已部署站点，不启动本地 webServer。
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
    baseURL: externalBaseURL || localBaseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: externalBaseURL ? undefined : {
    command: 'npm run dev',
    url: localBaseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
