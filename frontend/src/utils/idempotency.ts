type BrowserCrypto = Pick<Crypto, 'getRandomValues'> & Partial<Pick<Crypto, 'randomUUID'>>

/**
 * 生成请求幂等键。
 *
 * `crypto.randomUUID()` 只在安全上下文（HTTPS/localhost）可用，而内网 UAT
 * 可能通过普通 HTTP 主机名访问。`getRandomValues()` 在这类上下文仍可用，因此用
 * 它生成符合 RFC 4122 v4 格式的回退值，避免点击业务操作时同步抛错。
 */
export function newIdempotencyKey(source: BrowserCrypto = globalThis.crypto): string {
  if (typeof source.randomUUID === 'function') return source.randomUUID()

  const bytes = source.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`
}
