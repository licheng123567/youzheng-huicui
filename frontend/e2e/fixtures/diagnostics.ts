import { expect, type Page, type TestInfo } from '@playwright/test'

export type AllowedHttpFailure = {
  method: string
  path: RegExp
  statuses: number[]
}

export type Diagnostic = {
  kind: 'PAGE' | 'CONSOLE' | 'REQUEST' | 'HTTP'
  method?: string
  path?: string
  status?: number
  message: string
}

export function safePath(value: string): string {
  try {
    return new URL(value, 'http://invalid.local').pathname
  } catch {
    return value.split(/[?#]/, 1)[0]
  }
}

export function redactDiagnosticText(value: string): string {
  return value
    .replace(/(authorization\s*:\s*bearer\s+)[^\s,;]+/gi, '$1[REDACTED]')
    .replace(/(cookie\s*:\s*)[^\n]+/gi, '$1[REDACTED]')
    .replace(/("(?:password|token|secret|code)"\s*:\s*")[^"]*"/gi, '$1[REDACTED]"')
    .replace(/((?:password|token|secret|code)=)[^\s&#]+/gi, '$1[REDACTED]')
    .slice(0, 2_000)
}

export function classifyHttpFailure(
  method: string,
  url: string,
  status: number,
  allow: AllowedHttpFailure[],
): Diagnostic | null {
  if (status < 400) return null
  const normalizedMethod = method.toUpperCase()
  const path = safePath(url)
  const expected = allow.some(
    (rule) =>
      rule.method === normalizedMethod &&
      rule.path.test(path) &&
      rule.statuses.includes(status),
  )
  return expected
    ? null
    : {
        kind: 'HTTP',
        method: normalizedMethod,
        path,
        status,
        message: `${normalizedMethod} ${path} -> ${status}`,
      }
}

export function installDiagnostics(
  page: Page,
  testInfo: TestInfo,
  allow: AllowedHttpFailure[] = [],
) {
  const found: Diagnostic[] = []
  page.on('pageerror', (error) => {
    found.push({ kind: 'PAGE', message: redactDiagnosticText(error.message) })
  })
  page.on('console', (message) => {
    if (message.type() === 'error') {
      found.push({ kind: 'CONSOLE', message: redactDiagnosticText(message.text()) })
    }
  })
  page.on('requestfailed', (request) => {
    found.push({
      kind: 'REQUEST',
      method: request.method(),
      path: safePath(request.url()),
      message: redactDiagnosticText(request.failure()?.errorText ?? 'request failed'),
    })
  })
  page.on('response', (response) => {
    const diagnostic = classifyHttpFailure(
      response.request().method(),
      response.url(),
      response.status(),
      allow,
    )
    if (diagnostic) found.push(diagnostic)
  })

  return {
    async assertClean() {
      if (found.length) {
        await testInfo.attach('browser-diagnostics.json', {
          body: Buffer.from(JSON.stringify(found, null, 2)),
          contentType: 'application/json',
        })
      }
      expect(found, 'unexpected browser/network diagnostics').toEqual([])
    },
  }
}
