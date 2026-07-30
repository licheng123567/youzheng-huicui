# UAT Full Business Journey Scan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and run a repeatable six-role UAT scan that exercises the real Vue UI and Spring API with synthetic business data, captures safe diagnostics, fixes reproducible defects test-first, and deploys the verified result automatically.

**Architecture:** Keep the current two-test smoke unchanged and add an opt-in `full-scan` Playwright profile. A guarded entry point runs containerized static/build gates, resets only `huicui-uat`, isolates one destructive lifecycle from the broad regression, stores redacted artifacts, and leaves a clean seed for manual testing.

**Tech Stack:** Vue 3, TypeScript, Playwright 1.61, Spring Boot 3, Maven/JUnit 5, OpenAPI, Docker Compose, PostgreSQL 16, POSIX shell.

---

## File map

- `frontend/e2e/fixtures/diagnostics.ts`: redacted browser/network diagnostics.
- `frontend/e2e/diagnostics.spec.ts`: pure diagnostic contract tests.
- `frontend/e2e/role-page-scan.spec.ts`: role menu traversal derived from `nav.ts`.
- `frontend/e2e/mobile-role-scan.spec.ts`: PC/CO mobile route and overflow scan.
- `frontend/e2e/business-lifecycle.spec.ts`: import → dispatch → accept → assign → follow-up → repay → reconciliation.
- `frontend/playwright.full-scan.config.ts`: serialized desktop and mobile projects.
- `deploy/uat/Dockerfile.smoke`, `docker-compose.uat.yml`: package an opt-in full-scan service without changing smoke defaults.
- `deploy/uat/Dockerfile.full-scan-gate`: containerized Java, frontend, schema, nav, and route gates.
- `.dockerignore`: exclude worktrees, dependencies, build output, and Playwright artifacts from Docker contexts.
- `deploy/uat/full-scan.sh`, `deploy/uat/tests/full-scan-contract.sh`: guarded orchestration and safety contract.
- `deploy/uat/README.md`: scanning, artifacts, reset, and password recovery.
- `docs/superpowers/reports/2026-07-29-uat-business-journey-scan-report.md`: findings and verification evidence.

### Task 1: Capture the clean-UAT baseline

**Files:** Runtime artifacts only under `frontend/test-results/`, `frontend/playwright-report/`, and `/var/log/huicui-uat/`.

- [ ] **Step 1: Confirm tunnel, health, and deployed SHA**

```bash
curl --fail --silent --show-error http://127.0.0.1:6090/v1/actuator/health
ssh -o BatchMode=yes root@47.108.81.205 'cat /var/lib/huicui-uat/active-sha'
```

Expected: health is `UP`; SHA is 40 lowercase hex characters.

- [ ] **Step 2: Reset only the isolated UAT data**

```bash
ssh -o BatchMode=yes root@47.108.81.205 \
  'cd /root/huicui-uat-src && UAT_ENV_FILE=/root/huicui-uat.env UAT_STATE_DIR=/var/lib/huicui-uat ./deploy/uat/reset.sh --confirm huicui-uat'
```

Expected: `uat verify: PASS`; no non-UAT container or volume is named.

- [ ] **Step 3: Run the existing Playwright suite before adding scanners**

From `frontend/`:

```bash
set -eu
UAT_DEV_PASSWORD=$(ssh -o BatchMode=yes root@47.108.81.205 "awk 'index(\$0,\"UAT_DEV_PASSWORD=\")==1 { sub(/^UAT_DEV_PASSWORD=/,\"\"); print; exit }' /root/huicui-uat.env")
test -n "$UAT_DEV_PASSWORD"
export UAT_DEV_PASSWORD
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test --config=playwright.config.ts
unset UAT_DEV_PASSWORD
```

Expected: an objective pass/fail list. Preserve screenshots and traces before editing code.

- [ ] **Step 4: Record dependency findings without mutation**

```bash
mkdir -p uat-test-results
npm audit --json > uat-test-results/npm-audit-baseline.json || test "$?" -eq 1
```

Expected: JSON is saved; do not run `npm audit fix --force`.

### Task 2: Add credential-safe browser diagnostics with TDD

**Files:**
- Create: `frontend/e2e/fixtures/diagnostics.ts`
- Create: `frontend/e2e/diagnostics.spec.ts`

- [ ] **Step 1: Write the failing pure tests**

```ts
// frontend/e2e/diagnostics.spec.ts
import { test, expect } from '@playwright/test'
import { classifyHttpFailure, redactDiagnosticText, safePath } from './fixtures/diagnostics'

test('redacts credentials and query values', () => {
  const clean = redactDiagnosticText('password=Secret Authorization: Bearer abc Cookie: sid=xyz https://h/v1/me?token=raw {"password":"JsonSecret","token":"JsonToken"}')
  for (const secret of ['Secret', 'abc', 'sid=xyz', 'token=raw', 'JsonSecret', 'JsonToken']) expect(clean).not.toContain(secret)
  expect(clean).toContain('[REDACTED]')
})

test('requires an exact method/path/status allowlist match', () => {
  const allow = [{ method: 'GET', path: /^\/v1\/missing$/, statuses: [404] }]
  expect(classifyHttpFailure('GET', 'https://h/v1/missing?id=7', 404, allow)).toBeNull()
  expect(classifyHttpFailure('POST', 'https://h/v1/missing', 404, allow)?.kind).toBe('HTTP')
  expect(classifyHttpFailure('GET', 'https://h/v1/missing', 500, allow)?.kind).toBe('HTTP')
  expect(safePath('https://h/v1/cases?q=phone#x')).toBe('/v1/cases')
})
```

- [ ] **Step 2: Verify RED**

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test e2e/diagnostics.spec.ts
```

Expected: FAIL because `./fixtures/diagnostics` is missing.

- [ ] **Step 3: Implement the minimal collector**

```ts
// frontend/e2e/fixtures/diagnostics.ts
import { expect, type Page, type TestInfo } from '@playwright/test'

export type AllowedHttpFailure = { method: string; path: RegExp; statuses: number[] }
export type Diagnostic = { kind: 'PAGE' | 'CONSOLE' | 'REQUEST' | 'HTTP'; method?: string; path?: string; status?: number; message: string }

export function safePath(value: string): string {
  try { return new URL(value, 'http://invalid.local').pathname }
  catch { return value.split(/[?#]/, 1)[0] }
}
export function redactDiagnosticText(value: string): string {
  return value
    .replace(/(authorization\s*:\s*bearer\s+)[^\s,;]+/gi, '$1[REDACTED]')
    .replace(/(cookie\s*:\s*)[^\n]+/gi, '$1[REDACTED]')
    .replace(/("(?:password|token|secret|code)"\s*:\s*")[^"]*"/gi, '$1[REDACTED]"')
    .replace(/((?:password|token|secret|code)=)[^\s&#]+/gi, '$1[REDACTED]')
    .slice(0, 2_000)
}
export function classifyHttpFailure(method: string, url: string, status: number, allow: AllowedHttpFailure[]): Diagnostic | null {
  if (status < 400) return null
  const path = safePath(url)
  const expected = allow.some((r) => r.method === method.toUpperCase() && r.path.test(path) && r.statuses.includes(status))
  return expected ? null : { kind: 'HTTP', method: method.toUpperCase(), path, status, message: `${method.toUpperCase()} ${path} -> ${status}` }
}
export function installDiagnostics(page: Page, testInfo: TestInfo, allow: AllowedHttpFailure[] = []) {
  const found: Diagnostic[] = []
  page.on('pageerror', (e) => found.push({ kind: 'PAGE', message: redactDiagnosticText(e.message) }))
  page.on('console', (m) => { if (m.type() === 'error') found.push({ kind: 'CONSOLE', message: redactDiagnosticText(m.text()) }) })
  page.on('requestfailed', (r) => found.push({ kind: 'REQUEST', method: r.method(), path: safePath(r.url()), message: redactDiagnosticText(r.failure()?.errorText ?? 'request failed') }))
  page.on('response', (r) => { const d = classifyHttpFailure(r.request().method(), r.url(), r.status(), allow); if (d) found.push(d) })
  return { async assertClean() {
    if (found.length) await testInfo.attach('browser-diagnostics.json', { body: Buffer.from(JSON.stringify(found, null, 2)), contentType: 'application/json' })
    expect(found, 'unexpected browser/network diagnostics').toEqual([])
  } }
}
```

- [ ] **Step 4: Verify GREEN and commit**

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test e2e/diagnostics.spec.ts
git add frontend/e2e/diagnostics.spec.ts frontend/e2e/fixtures/diagnostics.ts
git commit -m "test(uat): add redacted browser diagnostics"
```

Expected: 2 passed.

### Task 3: Add the six-role desktop page scanner

**Files:**
- Modify: `frontend/e2e/helpers.ts`
- Create: `frontend/e2e/role-page-scan.spec.ts`

- [ ] **Step 1: Add a clean role switch helper**

```ts
// append to helpers.ts
export async function switchRole(page: Page, role: RoleKey, password = DEV_PW) {
  await page.goto('/login')
  await page.evaluate(() => localStorage.clear())
  await loginRole(page, role, password)
}
```

- [ ] **Step 2: Write the role-derived scanner**

```ts
// frontend/e2e/role-page-scan.spec.ts
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
      const item = page.getByRole('menuitem', { name: navLabel(path, role), exact: true })
      await expect(item).toBeVisible()
      await item.click()
      await expect(page).toHaveURL(new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?:$|[/?#])`))
      await expect(page.locator('main.body > *').first()).toBeVisible()
      await page.waitForTimeout(750)
      await diagnostic.assertClean()
    })
  }
  test(`${role} hides forbidden menu entries`, async ({ page }) => {
    await switchRole(page, role)
    for (const path of ALL_PATHS.filter((p) => !allowedPaths(role).includes(p))) {
      await expect(page.getByRole('menuitem', { name: navLabel(path, role), exact: true })).toHaveCount(0)
    }
  })
}
```

- [ ] **Step 3: Run and classify before any product edit**

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test e2e/role-page-scan.spec.ts
npm run e2e:navlint
```

Expected: every allowed route is attempted. A locator/data failure is investigated as `TEST_BUG`; a stable rule violation becomes a `PRODUCT_BUG` in Task 8.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/helpers.ts frontend/e2e/role-page-scan.spec.ts
git commit -m "test(uat): scan every role menu"
```

### Task 4: Add the PC/CO mobile scanner

**Files:** Create `frontend/e2e/mobile-role-scan.spec.ts`.

- [ ] **Step 1: Write the mobile route/detail/overflow scan**

```ts
import { test, expect, devices } from '@playwright/test'
import { switchRole } from './helpers'
import { installDiagnostics } from './fixtures/diagnostics'

test.use({ ...devices['iPhone 13'] })
for (const role of ['PC', 'CO'] as const) {
  test(`${role} mobile shell and case detail stay usable`, async ({ page }, testInfo) => {
    await switchRole(page, role)
    const diagnostic = installDiagnostics(page, testInfo)
    for (const path of ['/m', '/m/cases', '/m/calls', '/m/me']) {
      await page.goto(path)
      await expect(page.locator('.m-app')).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
    }
    await page.goto('/m/cases')
    const card = page.locator('.m-body .mc').first()
    await expect(card).toBeVisible(); await card.click()
    await expect(page).toHaveURL(/\/m\/cases\/\d+/)
    await diagnostic.assertClean()
  })
}
test('SA is redirected away from the mobile worker shell', async ({ page }) => {
  await switchRole(page, 'SA'); await page.goto('/m'); await expect(page).toHaveURL(/\/dashboard$/)
})
```

- [ ] **Step 2: Run and commit**

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test e2e/mobile-role-scan.spec.ts
git add frontend/e2e/mobile-role-scan.spec.ts
git commit -m "test(uat): scan mobile worker journeys"
```

Expected: 3 passed or an evidence-backed finding.

### Task 5: Add the isolated cross-role business lifecycle

**Files:**
- Modify: `frontend/e2e/helpers.ts`
- Create: `frontend/e2e/business-lifecycle.spec.ts`

- [ ] **Step 1: Add an authenticated same-origin API helper**

Change the helpers import to `import { Page, expect } from '@playwright/test'`, then append:

```ts
export async function authJson(page: Page, method: string, path: string, body?: unknown, expected = [200]) {
  const token = await page.evaluate(() => localStorage.getItem('token'))
  expect(token, 'authenticated token').toBeTruthy()
  const response = await page.request.fetch(path, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(['POST', 'PUT', 'PATCH'].includes(method) ? { 'Idempotency-Key': crypto.randomUUID() } : {}),
    },
    data: body,
  })
  const responseText = await response.text()
  expect(expected, `${method} ${path} returned ${response.status()}`).toContain(response.status())
  return responseText ? JSON.parse(responseText) : null
}
```

- [ ] **Step 2: Write the lifecycle test before changing product code**

Create `frontend/e2e/business-lifecycle.spec.ts`:

```ts
import { test, expect } from '@playwright/test'
import { authJson, openCaseByAcctNo, switchRole } from './helpers'

test('@lifecycle import -> dispatch -> accept -> assign -> follow-up -> repay -> reconcile', async ({ page }) => {
  const runId = `E2E-${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}-${Math.random().toString(36).slice(2, 7)}`
  const acctNo = `${runId}-ACCT`
  const phone = `139${String(Date.now()).slice(-8)}`

  await switchRole(page, 'SA')
  const projects = await authJson(page, 'GET', '/v1/projects?page=1&size=200') as any
  const project = projects.items.find((x: any) => x.name === '翠湖一期') ?? projects.items[0]
  expect(project).toBeTruthy()
  await authJson(page, 'POST', '/v1/batches/import', {
    projectId: String(project.id), commInRate: 0.3,
    rows: [{ acctNo, ownerName: `合成业主-${runId}`, phone, room: `合成房号-${runId}`, dueCents: 100_000, arrearPeriod: '2026-01~2026-06' }],
  })
  const imported = await authJson(page, 'GET', `/v1/cases?q=${encodeURIComponent(acctNo)}&page=1&size=20`) as any
  expect(imported.items).toHaveLength(1)
  const created = imported.items[0]
  const providers = await authJson(page, 'GET', '/v1/orgs?type=PROVIDER&status=ACTIVE&page=1&size=50') as any
  const provider = providers.items.find((x: any) => x.name === '捷信催收')
  expect(provider).toBeTruthy()
  await authJson(page, 'PUT', `/v1/batches/${created.batchId}/commission-rates`, { commInRate: 0.3, payOutRate: 0.2 })
  await authJson(page, 'POST', `/v1/batches/${created.batchId}/dispatch`, { mode: 'WHOLE', providerId: String(provider.id), payOutRate: 0.2 })

  await switchRole(page, 'VL')
  await authJson(page, 'POST', `/v1/cases/${created.id}/accept`)
  const members = await authJson(page, 'GET', '/v1/members?page=1&size=100') as any
  const collector = members.items.find((x: any) => x.username === 'jx_co1')
  expect(collector).toBeTruthy()
  await authJson(page, 'POST', `/v1/cases/${created.id}/assign`, { collectorId: String(collector.id) })

  await switchRole(page, 'CO')
  await openCaseByAcctNo(page, acctNo)
  await page.getByRole('button', { name: '写跟进' }).click()
  const follow = page.getByRole('dialog').filter({ hasText: '写跟进记录' })
  await follow.getByPlaceholder(/记录本次跟进情况/).fill(runId)
  await follow.getByRole('button', { name: '提交', exact: true }).click()
  await expect(follow).toBeHidden()

  await switchRole(page, 'PC')
  await openCaseByAcctNo(page, acctNo)
  await page.locator('.dtabs .t').filter({ hasText: '沟通记录' }).click()
  await expect(page.getByText(runId).first()).toBeVisible()
  await page.getByRole('button', { name: '标线下回款' }).click()
  const repay = page.getByRole('dialog').filter({ hasText: '标线下回款' })
  await repay.locator('input[type="number"]').fill('12.34')
  await repay.locator('input[type="date"]').fill(new Date().toISOString().slice(0, 10))
  await repay.locator('select').selectOption('BANK_TRANSFER')
  await repay.getByPlaceholder(/凭证说明/).fill(runId)
  await repay.getByRole('button', { name: '提交', exact: true }).click()
  await expect(repay).toBeHidden()

  await switchRole(page, 'SA')
  const lines = await authJson(page, 'GET', `/v1/batches/${created.batchId}/repay-lines?page=1&size=100`) as any
  expect(lines.items.some((x: any) => x.caseId === String(created.id) && x.amountCents === 1234)).toBe(true)
  await page.goto('/settlement')
  await expect(page.locator('table tbody tr').filter({ hasText: '¥12.34' }).first()).toBeVisible()
})
```

- [ ] **Step 3: Run in a reset sandwich**

Run the protected reset from Task 1, then:

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test e2e/business-lifecycle.spec.ts
```

Preserve artifacts and run the protected reset again whether the test passes or fails.

Expected: PASS or one exact failing boundary. Do not weaken permissions or replace failed UI assertions with direct database writes.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/helpers.ts frontend/e2e/business-lifecycle.spec.ts
git commit -m "test(uat): exercise cross-role business lifecycle"
```

### Task 6: Package the opt-in Playwright full scan

**Files:**
- Create: `frontend/playwright.full-scan.config.ts`
- Modify: `deploy/uat/Dockerfile.smoke`
- Modify: `deploy/uat/docker-compose.uat.yml`

- [ ] **Step 1: Add the serialized full-scan config**

```ts
// frontend/playwright.full-scan.config.ts
import { defineConfig, devices } from '@playwright/test'
const artifacts = process.env.PLAYWRIGHT_ARTIFACT_DIR ?? 'full-scan-results'
export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, retries: 0, timeout: 45_000,
  reporter: [['list'], ['json', { outputFile: `${artifacts}/results.json` }], ['html', { outputFolder: `${artifacts}/html`, open: 'never' }]],
  outputDir: `${artifacts}/artifacts`,
  use: { baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://web', trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'retain-on-failure' },
  projects: [
    { name: 'desktop-chromium', testIgnore: /mobile-role-scan\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-chromium', testMatch: /mobile-role-scan\.spec\.ts/, use: { ...devices['iPhone 13'] } },
  ],
})
```

- [ ] **Step 2: Expand the Playwright image without changing smoke default**

Replace the config/E2E copy section of `deploy/uat/Dockerfile.smoke` with:

```dockerfile
COPY frontend/playwright.uat.config.ts frontend/playwright.full-scan.config.ts ./
COPY frontend/e2e ./e2e
COPY frontend/src/constants/nav.ts ./src/constants/nav.ts
ENTRYPOINT ["npx", "playwright", "test", "--config=playwright.uat.config.ts"]
```

- [ ] **Step 3: Add the opt-in Compose service**

```yaml
  full-scan:
    image: huicui-uat-smoke:${UAT_IMAGE_TAG:?UAT_IMAGE_TAG is required}
    profiles: [full-scan]
    depends_on:
      web: { condition: service_healthy }
    entrypoint: ["npx", "playwright", "test", "--config=playwright.full-scan.config.ts"]
    environment:
      UAT_DEV_PASSWORD: ${UAT_DEV_PASSWORD:?UAT_DEV_PASSWORD is required}
      PLAYWRIGHT_BASE_URL: http://web
      PLAYWRIGHT_ARTIFACT_DIR: /artifacts
    volumes:
      - { type: bind, source: "${UAT_ARTIFACT_DIR:?UAT_ARTIFACT_DIR is required}", target: /artifacts }
    mem_limit: 768M
    networks: [uat]
```

- [ ] **Step 4: Render both profiles and commit**

```bash
UAT_IMAGE_TAG=$(git rev-parse HEAD) docker compose --project-name huicui-uat --env-file deploy/uat/.env.example -f deploy/uat/docker-compose.uat.yml --profile smoke config --quiet
UAT_IMAGE_TAG=$(git rev-parse HEAD) docker compose --project-name huicui-uat --env-file deploy/uat/.env.example -f deploy/uat/docker-compose.uat.yml --profile full-scan config --quiet
git add frontend/playwright.full-scan.config.ts deploy/uat/Dockerfile.smoke deploy/uat/docker-compose.uat.yml
git commit -m "feat(uat): package opt-in full scan"
```

Expected: both profiles render; smoke still selects only `uat-smoke.spec.ts`.

### Task 7: Add the guarded runner and server-side build gate

**Files:**
- Create: `.dockerignore`
- Create: `deploy/uat/Dockerfile.full-scan-gate`
- Create: `deploy/uat/full-scan.sh`
- Create: `deploy/uat/tests/full-scan-contract.sh`
- Modify: `deploy/uat/tests/static-contract.sh`
- Modify: `deploy/uat/README.md`

- [ ] **Step 1: Write the failing safety contract**

```sh
#!/bin/sh
# deploy/uat/tests/full-scan-contract.sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
RUNNER="$ROOT/deploy/uat/full-scan.sh"; COMPOSE="$ROOT/deploy/uat/docker-compose.uat.yml"; GATE="$ROOT/deploy/uat/Dockerfile.full-scan-gate"
fail() { echo "full-scan contract: FAIL: $*" >&2; exit 1; }
[ -x "$RUNNER" ] || fail 'runner missing or not executable'
[ -f "$GATE" ] || fail 'gate Dockerfile missing'
grep -Eq -- '--confirm.*huicui-uat' "$RUNNER" || fail 'explicit confirmation missing'
grep -Eq 'reset\.sh.*--confirm[[:space:]]+huicui-uat' "$RUNNER" || fail 'guarded reset missing'
grep -Eq -- '--profile[[:space:]]+full-scan.*run[[:space:]]+--rm[[:space:]]+full-scan' "$RUNNER" || fail 'compose run missing'
grep -Eq 'business-lifecycle\.spec\.ts' "$RUNNER" || fail 'lifecycle phase missing'
grep -Eq -- '--grep-invert[[:space:]]+@lifecycle' "$RUNNER" || fail 'regression isolation missing'
grep -Eq 'profiles:[[:space:]]*\[full-scan\]' "$COMPOSE" || fail 'profile missing'
grep -Eq '^FROM[[:space:]]+maven:3\.9-eclipse-temurin-21' "$GATE" || fail 'Java 21 gate missing'
grep -Eq '^FROM[[:space:]]+node:22' "$GATE" || fail 'Node 22 gate missing'
grep -Eq 'route_coverage\.py' "$GATE" || fail 'route gate missing'
if grep -Eq 'UAT_DEV_PASSWORD=.*(docker|playwright)|Admin@123' "$RUNNER"; then fail 'password entered argv/source'; fi
echo 'full-scan contract: PASS'
```

Make it executable and run it. Expected RED: runner or gate is missing.

- [ ] **Step 2: Add the containerized Java/frontend/route gate**

Create `.dockerignore` first:

```gitignore
.git
.worktrees
**/node_modules
frontend/dist
frontend/test-results
frontend/playwright-report
frontend/uat-test-results
frontend/full-scan-results
backend/app/target
```

```dockerfile
# syntax=docker/dockerfile:1.7
# deploy/uat/Dockerfile.full-scan-gate
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src
COPY backend/app/pom.xml backend/app/pom.xml
COPY backend/app/src backend/app/src
RUN --mount=type=cache,target=/root/.m2/repository mvn -f backend/app/pom.xml -B test
RUN mkdir -p /proof && printf 'backend-tests-pass\n' >/proof/backend

FROM node:22-bookworm AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src ./src
COPY frontend/e2e ./e2e
COPY frontend/scripts ./scripts
COPY docs/api/openapi-core.yaml /src/docs/api/openapi-core.yaml
RUN cp src/api/schema.d.ts /tmp/schema.d.ts && npm run gen:api && cmp /tmp/schema.d.ts src/api/schema.d.ts
RUN npm run e2e:navlint && npm run build
RUN mkdir -p /proof && printf 'frontend-gates-pass\n' >/proof/frontend

FROM python:3.13-slim AS routes
WORKDIR /src
RUN pip install --no-cache-dir PyYAML==6.0.2
COPY backend/scripts/route_coverage.py backend/scripts/route_coverage.py
COPY backend/app/src backend/app/src
COPY docs/api/openapi-core.yaml docs/api/openapi-core.yaml
RUN python3 backend/scripts/route_coverage.py
RUN mkdir -p /proof && printf 'route-coverage-pass\n' >/proof/routes

FROM alpine:3.22
COPY --from=backend /proof/backend /proof/backend
COPY --from=frontend /proof/frontend /proof/frontend
COPY --from=routes /proof/routes /proof/routes
CMD ["sh", "-c", "cat /proof/backend /proof/frontend /proof/routes"]
```

- [ ] **Step 3: Add the fail-closed single entry point**

```sh
#!/bin/sh
# deploy/uat/full-scan.sh
set -eu
umask 077
fail() { echo "uat full-scan: FAIL: $*" >&2; exit 1; }
[ "$#" -eq 2 ] && [ "$1" = '--confirm' ] && [ "$2" = 'huicui-uat' ] || fail 'usage: full-scan.sh --confirm huicui-uat'
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd); ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}; STATE_DIR=${UAT_STATE_DIR:-/var/lib/huicui-uat}
ARTIFACT_ROOT=${UAT_ARTIFACT_ROOT:-/var/log/huicui-uat/full-scan}; COMPOSE="$SCRIPT_DIR/docker-compose.uat.yml"; DOCKER=${UAT_DOCKER_BIN:-docker}
[ -f "$ENV_FILE" ] || fail "environment file missing: $ENV_FILE"
[ -f "$STATE_DIR/active-sha" ] || fail 'active SHA missing'
sha=$(sed -n '1p' "$STATE_DIR/active-sha"); [ "${#sha}" -eq 40 ] || fail 'active SHA must be 40 characters'
case "$sha" in *[!0-9a-f]*) fail 'active SHA must be lowercase hex';; esac
source_sha=$(git -C "$ROOT" rev-parse HEAD); [ "$source_sha" = "$sha" ] || fail "source SHA $source_sha does not match active SHA $sha"
run_dir="$ARTIFACT_ROOT/$(date -u +%Y%m%dT%H%M%SZ)-$sha"
mkdir -p "$run_dir/gate" "$run_dir/lifecycle" "$run_dir/regression"
compose_run() { artifact=$1; shift; UAT_IMAGE_TAG=$sha UAT_ARTIFACT_DIR="$artifact" "$DOCKER" compose --project-name huicui-uat --env-file "$ENV_FILE" -f "$COMPOSE" --profile full-scan run --rm full-scan "$@"; }
reset_uat() { UAT_ENV_FILE=$ENV_FILE UAT_STATE_DIR=$STATE_DIR "$SCRIPT_DIR/reset.sh" --confirm huicui-uat; }
"$SCRIPT_DIR/tests/static-contract.sh"
"$DOCKER" build -f "$SCRIPT_DIR/Dockerfile.full-scan-gate" -t "huicui-uat-gate:$sha" "$ROOT"
"$DOCKER" run --rm "huicui-uat-gate:$sha" >"$run_dir/gate/result.txt"
reset_uat
set +e; compose_run "$run_dir/lifecycle" e2e/business-lifecycle.spec.ts; lifecycle_status=$?; set -e
reset_uat
[ "$lifecycle_status" -eq 0 ] || fail "lifecycle failed; artifacts: $run_dir/lifecycle"
set +e; compose_run "$run_dir/regression" --grep-invert @lifecycle; regression_status=$?; set -e
reset_uat
[ "$regression_status" -eq 0 ] || fail "regression failed; artifacts: $run_dir/regression"
UAT_ENV_FILE=$ENV_FILE UAT_ARTIFACT_DIR="$run_dir/final-verify" "$SCRIPT_DIR/verify.sh"
printf '%s\n' "$sha" >"$STATE_DIR/full-scan-pass-sha"
printf 'uat full-scan: PASS %s artifacts=%s\n' "$sha" "$run_dir"
```

- [ ] **Step 4: Wire contracts into the existing static gate**

Replace the current final `frontend/playwright.uat.config.ts` entry in the required-file loop in `deploy/uat/tests/static-contract.sh` with this complete tail:

```sh
  "$REPO_ROOT/frontend/playwright.uat.config.ts" \
  "$REPO_ROOT/.dockerignore" \
  "$UAT_DIR/Dockerfile.full-scan-gate" \
  "$UAT_DIR/full-scan.sh" \
  "$UAT_DIR/tests/full-scan-contract.sh" \
  "$REPO_ROOT/frontend/playwright.full-scan.config.ts" \
  "$REPO_ROOT/frontend/e2e/diagnostics.spec.ts" \
  "$REPO_ROOT/frontend/e2e/role-page-scan.spec.ts" \
  "$REPO_ROOT/frontend/e2e/mobile-role-scan.spec.ts" \
  "$REPO_ROOT/frontend/e2e/business-lifecycle.spec.ts"
```

Add the executable checks and contract invocation after the existing executable checks:

```sh
require_executable "$UAT_DIR/full-scan.sh"
require_executable "$UAT_DIR/tests/full-scan-contract.sh"
"$UAT_DIR/tests/full-scan-contract.sh"
```

Run:

```bash
chmod +x deploy/uat/full-scan.sh deploy/uat/tests/full-scan-contract.sh
deploy/uat/tests/full-scan-contract.sh
deploy/uat/tests/static-contract.sh
```

Expected GREEN: both contracts pass.

- [ ] **Step 5: Document full scan and forgotten-password recovery**

Add these exact operational rules to `deploy/uat/README.md`:

````markdown
## 全角色完整扫描

完整扫描会重建 `huicui-uat-pgdata`，仅在无需保留人工验收数据时运行：

```sh
cd /root/huicui-uat-src
UAT_ENV_FILE=/root/huicui-uat.env UAT_STATE_DIR=/var/lib/huicui-uat \
  ./deploy/uat/full-scan.sh --confirm huicui-uat
```

产物位于 `/var/log/huicui-uat/full-scan/YYYYMMDDTHHMMSSZ-40位提交SHA/`。

macOS 可把随机 UAT 口令直接复制到剪贴板而不显示明文：

```sh
ssh -o BatchMode=yes root@47.108.81.205 \
  "awk 'index(\$0,\"UAT_DEV_PASSWORD=\")==1 { sub(/^UAT_DEV_PASSWORD=/,\"\"); print; exit }' /root/huicui-uat.env" | pbcopy
```

不要修改共享合成账号密码；若已修改且忘记，运行受保护的 `reset.sh --confirm huicui-uat`，重建后恢复环境文件中的随机口令。
````

- [ ] **Step 6: Commit**

```bash
git add .dockerignore deploy/uat/Dockerfile.full-scan-gate deploy/uat/full-scan.sh deploy/uat/tests/full-scan-contract.sh deploy/uat/tests/static-contract.sh deploy/uat/README.md
git commit -m "feat(uat): orchestrate guarded full scan"
```

### Task 8: Execute, classify, and fix confirmed product defects

**Files:**
- Create: `docs/superpowers/reports/2026-07-29-uat-business-journey-scan-report.md`
- Modify only source files proven by a reproduced `PRODUCT_BUG`
- Add one focused regression test per product defect

- [ ] **Step 1: Run the new local scanners against the clean deployed UAT**

```bash
set -u
export UAT_DEV_PASSWORD="$(ssh -o BatchMode=yes root@47.108.81.205 \
  "awk 'index(\$0,\"UAT_DEV_PASSWORD=\")==1 { sub(/^UAT_DEV_PASSWORD=/,\"\"); print; exit }' /root/huicui-uat.env")"
test -n "$UAT_DEV_PASSWORD"
ssh -o BatchMode=yes root@47.108.81.205 \
  'cd /root/huicui-uat-src && UAT_ENV_FILE=/root/huicui-uat.env UAT_STATE_DIR=/var/lib/huicui-uat ./deploy/uat/reset.sh --confirm huicui-uat'
cd frontend
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test --config=playwright.full-scan.config.ts
scan_status=$?
cd ..
ssh -o BatchMode=yes root@47.108.81.205 \
  'cd /root/huicui-uat-src && UAT_ENV_FILE=/root/huicui-uat.env UAT_STATE_DIR=/var/lib/huicui-uat ./deploy/uat/reset.sh --confirm huicui-uat'
unset UAT_DEV_PASSWORD
test "$scan_status" -eq 0
```

Expected: PASS or one exact local artifact. The second reset is mandatory even after a scan failure; preserve the failing artifacts for classification.

- [ ] **Step 2: Classify every failure before editing product code**

Use this report table:

```markdown
| ID | Role/path | Reproduction | Evidence | Class | Root cause | Action |
| --- | --- | --- | --- | --- | --- | --- |
```

Classes are fixed: `PRODUCT_BUG`, `TEST_BUG`, `EXPECTED_DENIAL`, `ENVIRONMENT`, `REQUIREMENT_REVIEW`.

- [ ] **Step 3: For each PRODUCT_BUG, prove RED, fix one root cause, prove GREEN**

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:6090 npx playwright test --last-failed
```

Expected before fix: failure at the reproduced assertion, not setup or timeout. After the minimal source change, rerun the identical command, the nearest feature spec, and the relevant backend test. If the API schema changes, run `npm run gen:api` and include the generated schema in the same commit.

- [ ] **Step 4: Commit findings independently**

After `git status --short` confirms only the focused regression and root-cause edits are present:

```bash
git add -u
git commit -m "fix(uat): resolve verified full-scan defect"
```

Do not combine unrelated findings. Requirement ambiguity stays in the report and does not trigger a code change.

- [ ] **Step 5: Complete and commit the scan report**

The report records active SHA, commands, pass/fail counts, all classifications, dependency audit summary, fixes, tests, unresolved requirements, and artifact paths.

```bash
git add docs/superpowers/reports/2026-07-29-uat-business-journey-scan-report.md
git commit -m "docs(uat): report full journey scan findings"
```

### Task 9: Verify, integrate, and automatically deploy

**Files:** Verification only unless review finds a confirmed defect.

- [ ] **Step 1: Run every local gate**

```bash
python3 backend/scripts/route_coverage.py
deploy/uat/tests/static-contract.sh
cd frontend
npm run gen:api
git diff --exit-code -- src/api/schema.d.ts
npm run e2e:navlint
npm run build
cd ../backend/app
mvn -DargLine=-javaagent:/Users/shuo/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dnet.bytebuddy.experimental=true test
```

Expected: 175/175 routes, both static contracts, no schema drift, nav lint/build, and 39 or more backend tests all pass.

- [ ] **Step 2: Review scope and secret hygiene**

```bash
git diff --stat main...HEAD
git diff --check main...HEAD
git log --oneline main..HEAD
git status --short
```

Confirm the approved design is covered and no secret, production data, artifact, `node_modules`, or password is tracked.

- [ ] **Step 3: Fast-forward main and push the internal test remote**

From the main worktree:

```bash
git merge --ff-only feat/uat-business-journey-scan
git push backup main:main
```

Expected: push returns quickly; the post-receive worker deploys asynchronously.

- [ ] **Step 4: Wait for automatic deployment and verify isolation**

```bash
ssh -o BatchMode=yes root@47.108.81.205 '
set -eu
active=$(cat /var/lib/huicui-uat/active-sha)
main=$(git --git-dir=/root/repos/youzheng-huicui.git rev-parse refs/heads/main)
test "$active" = "$main"
cd /root/huicui-uat-src
UAT_ENV_FILE=/root/huicui-uat.env ./deploy/uat/verify.sh
docker ps --filter name=huicui-backend-1 --filter name=huicui-db-1 --format "{{.Names}} {{.Status}}"
docker ps --filter name=huicui-uat- --format "{{.Names}} {{.Status}}"
'
```

Expected: active SHA equals bare `main`; UAT verify passes; the legacy stack and all UAT containers remain healthy.

- [ ] **Step 5: Run the server-side full scan on the active SHA**

```bash
ssh -o BatchMode=yes root@47.108.81.205 \
  'cd /root/huicui-uat-src && UAT_ENV_FILE=/root/huicui-uat.env UAT_STATE_DIR=/var/lib/huicui-uat ./deploy/uat/full-scan.sh --confirm huicui-uat'
```

Expected: lifecycle and regression pass, the final reset succeeds, and `verify.sh` passes. If this post-deploy gate finds a product defect, return to Task 8, add a focused RED/GREEN fix commit on the feature branch, fast-forward `main` again, and repeat Steps 3-5; never mark a failing SHA as complete.

---

## Plan self-review

- Spec coverage: Tasks 2-5 cover diagnostics, all roles, mobile, and the business lifecycle; Tasks 6-7 cover packaging, gates, reset isolation, artifacts, and password recovery; Tasks 8-9 cover classification, TDD fixes, reporting, verification, and deployment.
- Safety: only `reset.sh --confirm huicui-uat` removes the UAT volume; production data and live supplier calls remain excluded; passwords stay in environment/clipboard, never Git, argv, or artifacts.
- Type consistency: `RoleKey`, `switchRole`, `authJson`, `AllowedHttpFailure`, `Diagnostic`, and `installDiagnostics` use identical names at definition and call sites.
- Isolation: the lifecycle is reset before regression, `@lifecycle` is excluded from regression, and a final reset returns UAT to a clean synthetic seed.
