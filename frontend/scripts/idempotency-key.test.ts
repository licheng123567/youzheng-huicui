import assert from 'node:assert/strict'
import test from 'node:test'
import { newIdempotencyKey } from '../src/utils/idempotency'

test('uses randomUUID when the browser exposes it', () => {
  const source = {
    randomUUID: () => 'native-uuid',
    getRandomValues: () => {
      throw new Error('fallback must not run')
    },
  } as unknown as Crypto

  assert.equal(newIdempotencyKey(source), 'native-uuid')
})

test('creates an RFC 4122 v4 key when randomUUID is unavailable on an insecure origin', () => {
  const source = {
    getRandomValues: <T extends ArrayBufferView | null>(array: T): T => {
      const bytes = new Uint8Array(array!.buffer, array!.byteOffset, array!.byteLength)
      bytes.set([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])
      return array
    },
  } as Crypto

  assert.equal(newIdempotencyKey(source), '00010203-0405-4607-8809-0a0b0c0d0e0f')
})
