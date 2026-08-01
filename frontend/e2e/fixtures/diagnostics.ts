import { expect, type Page, type Request, type TestInfo } from '@playwright/test'

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
    .replace(/([?&][^=\s&#]+)=([^&#\s]+)/g, '$1=[REDACTED]')
    .replace(/(authorization\s*:\s*bearer\s+)[^\s,;]+/gi, '$1[REDACTED]')
    .replace(/(cookie\s*:\s*)[^\n]+/gi, '$1[REDACTED]')
    .replace(/("(?:password|pass|pwd|token|secret|code|session[_-]?id|api[_-]?key|authorization)"\s*:\s*")[^"]*"/gi, '$1[REDACTED]"')
    .replace(/((?:password|pass|pwd|token|secret|code|session[_-]?id|api[_-]?key)=)[^\s&#]+/gi, '$1[REDACTED]')
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

export function isBenignEmptyResponseAbort(
  errorText: string | undefined,
  status: number | undefined,
  contentLength: string | undefined,
): boolean {
  return errorText === 'net::ERR_ABORTED' && status != null && status >= 200 && status < 300 && contentLength === '0'
}

export function tracksForQuiescence(url: string): boolean {
  return safePath(url).startsWith('/v1/')
}

export function installDiagnostics(
  page: Page,
  testInfo: TestInfo,
  allow: AllowedHttpFailure[] = [],
) {
  const found: Diagnostic[] = []
  const pendingRequests = new Set<Request>()
  const successfulEmptyResponses = new WeakMap<Request, { status: number; contentLength: string }>()
  let lastNetworkActivity = Date.now()

  page.on('request', (request) => {
    if (tracksForQuiescence(request.url())) {
      pendingRequests.add(request)
      lastNetworkActivity = Date.now()
    }
  })
  page.on('requestfinished', (request) => {
    if (pendingRequests.delete(request)) lastNetworkActivity = Date.now()
  })
  page.on('pageerror', (error) => {
    found.push({ kind: 'PAGE', message: redactDiagnosticText(error.message) })
  })
  page.on('console', (message) => {
    if (
      message.type() === 'error' &&
      !/^Failed to load resource: the server responded with a status of \d+/i.test(message.text())
    ) {
      found.push({ kind: 'CONSOLE', message: redactDiagnosticText(message.text()) })
    }
  })
  page.on('requestfailed', (request) => {
    if (pendingRequests.delete(request)) lastNetworkActivity = Date.now()
    const errorText = request.failure()?.errorText
    const successfulEmptyResponse = successfulEmptyResponses.get(request)
    if (isBenignEmptyResponseAbort(
      errorText,
      successfulEmptyResponse?.status,
      successfulEmptyResponse?.contentLength,
    )) return
    found.push({
      kind: 'REQUEST',
      method: request.method(),
      path: safePath(request.url()),
      message: redactDiagnosticText(errorText ?? 'request failed'),
    })
  })
  page.on('response', (response) => {
    const contentLength = response.headers()['content-length']
    if (response.status() >= 200 && response.status() < 300 && contentLength === '0') {
      successfulEmptyResponses.set(response.request(), { status: response.status(), contentLength })
    }
    const diagnostic = classifyHttpFailure(
      response.request().method(),
      response.url(),
      response.status(),
      allow,
    )
    if (diagnostic) found.push(diagnostic)
  })

  return {
    async waitForQuiescence(timeoutMs = 5_000, quietMs = 250) {
      const deadline = Date.now() + timeoutMs
      while (Date.now() < deadline) {
        if (pendingRequests.size === 0 && Date.now() - lastNetworkActivity >= quietMs) return
        await page.waitForTimeout(50).catch(() => undefined)
      }
      const pending = [...pendingRequests]
      found.push({
        kind: 'REQUEST',
        path: pending[0] ? safePath(pending[0].url()) : undefined,
        message: pending.length
          ? `${pending.length} request(s) still in flight after ${timeoutMs}ms`
          : `network did not remain quiet for ${quietMs}ms within ${timeoutMs}ms`,
      })
    },
    async assertClean(shouldAssert = true) {
      if (found.length) {
        await testInfo.attach('browser-diagnostics.json', {
          body: Buffer.from(JSON.stringify(found, null, 2)),
          contentType: 'application/json',
        })
      }
      if (shouldAssert) {
        expect(found, 'unexpected browser/network diagnostics').toEqual([])
      }
    },
  }
}
