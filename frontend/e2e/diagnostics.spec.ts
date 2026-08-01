import { test, expect } from './fixtures/test'
import { classifyHttpFailure, isBenignEmptyResponseAbort, redactDiagnosticText, safePath, tracksForQuiescence } from './fixtures/diagnostics'
import { authJson } from './helpers'

test('redacts credentials and query values', () => {
  const clean = redactDiagnosticText(
    'password=Secret Authorization: Bearer abc {"password":"JsonSecret","token":"JsonToken","session_id":"JsonSession"}\nCookie: sid=xyz\nhttps://h/v1/me?token=raw&ownerPhone=13900000001&unknown=private /relative?api_key=key&room=A101',
  )
  for (const secret of [
    'Secret',
    'abc',
    'sid=xyz',
    'token=raw',
    '13900000001',
    'private',
    'A101',
    'JsonSecret',
    'JsonToken',
    'JsonSession',
  ]) {
    expect(clean).not.toContain(secret)
  }
  expect(clean).toContain('ownerPhone=[REDACTED]')
  expect(clean).toContain('room=[REDACTED]')
  expect(clean).not.toContain('api_key=key')
})

test('requires an exact method/path/status allowlist match', () => {
  const allow = [{ method: 'GET', path: /^\/v1\/missing$/, statuses: [404] }]
  expect(classifyHttpFailure('GET', 'https://h/v1/missing?id=7', 404, allow)).toBeNull()
  expect(classifyHttpFailure('POST', 'https://h/v1/missing', 404, allow)?.kind).toBe('HTTP')
  expect(classifyHttpFailure('GET', 'https://h/v1/missing', 500, allow)?.kind).toBe('HTTP')
  expect(safePath('https://h/v1/cases?q=phone#x')).toBe('/v1/cases')
})

test('only ignores Chromium aborts after a successful empty response', () => {
  expect(isBenignEmptyResponseAbort('net::ERR_ABORTED', 200, '0')).toBe(true)
  expect(isBenignEmptyResponseAbort('net::ERR_ABORTED', 204, '0')).toBe(true)
  expect(isBenignEmptyResponseAbort('net::ERR_ABORTED', undefined, '0')).toBe(false)
  expect(isBenignEmptyResponseAbort('net::ERR_ABORTED', 500, '0')).toBe(false)
  expect(isBenignEmptyResponseAbort('net::ERR_ABORTED', 200, '12')).toBe(false)
  expect(isBenignEmptyResponseAbort('net::ERR_CONNECTION_RESET', 200, '0')).toBe(false)
})

test('waits for API quiescence without treating Vite source modules as business requests', () => {
  expect(tracksForQuiescence('http://127.0.0.1:6091/v1/me')).toBe(true)
  expect(tracksForQuiescence('http://127.0.0.1:6091/src/layouts/AppLayout.vue')).toBe(false)
})

test('authenticated API helper never passes bearer credentials through Node request context', async () => {
  let evaluateArgs: unknown
  const fakePage = {
    request: {
      fetch: () => {
        throw new Error('unsafe Playwright request context was used')
      },
    },
    evaluate: async (_callback: unknown, args: unknown) => {
      evaluateArgs = args
      return { authenticated: true, status: 200, responseText: '{"ok":true}' }
    },
  }

  await expect(
    authJson(fakePage as any, 'POST', '/v1/secure', { synthetic: true }),
  ).resolves.toEqual({ ok: true })
  const serialized = JSON.stringify(evaluateArgs)
  expect(serialized).not.toMatch(/authorization|bearer|password|token/i)
})
