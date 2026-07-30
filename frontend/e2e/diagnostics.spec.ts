import { test, expect } from '@playwright/test'
import { classifyHttpFailure, redactDiagnosticText, safePath } from './fixtures/diagnostics'

test('redacts credentials and query values', () => {
  const clean = redactDiagnosticText(
    'password=Secret Authorization: Bearer abc {"password":"JsonSecret","token":"JsonToken"}\nCookie: sid=xyz\nhttps://h/v1/me?token=raw',
  )
  for (const secret of ['Secret', 'abc', 'sid=xyz', 'token=raw', 'JsonSecret', 'JsonToken']) {
    expect(clean).not.toContain(secret)
  }
  expect(clean).toContain('[REDACTED]')
})

test('requires an exact method/path/status allowlist match', () => {
  const allow = [{ method: 'GET', path: /^\/v1\/missing$/, statuses: [404] }]
  expect(classifyHttpFailure('GET', 'https://h/v1/missing?id=7', 404, allow)).toBeNull()
  expect(classifyHttpFailure('POST', 'https://h/v1/missing', 404, allow)?.kind).toBe('HTTP')
  expect(classifyHttpFailure('GET', 'https://h/v1/missing', 500, allow)?.kind).toBe('HTTP')
  expect(safePath('https://h/v1/cases?q=phone#x')).toBe('/v1/cases')
})
