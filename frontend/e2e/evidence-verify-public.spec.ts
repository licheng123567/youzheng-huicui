import { test, expect } from '@playwright/test'

// BR-M6 二维码公开核验：无登录态访问 /evidence/{id}/verify(扫码核验)：
// 有效存证返回 valid+hash+certNo，篡改/不存在返回 valid=false 或 404，且不泄露 org/owner 明细。
// 注：verify 为后端 public 端点（前端代理 /v1）。本 spec 直接用 request 上下文打公开端点。
test.describe('BR-M6 存证公开核验(无登录态)', () => {
  test('有效存证→valid:true 且含 hash/certNo', async ({ request }) => {
    // 取一条种子存证 id（公开 verify 不需鉴权；id=1 为种子首条）
    const res = await request.get('/v1/evidence/1/verify')
    expect(res.status()).toBe(200)
    const body = await res.json()
    expect(body.valid).toBe(true)
    expect(body.hash).toBeTruthy()
    expect(body.certNo).toBeTruthy()
    // 不泄露组织/业主明细
    expect(body.org).toBeUndefined()
    expect(body.owner).toBeUndefined()
  })

  test('不存在存证→404 或 valid:false', async ({ request }) => {
    const res = await request.get('/v1/evidence/999999/verify')
    if (res.status() === 404) {
      expect(res.status()).toBe(404)
    } else {
      expect(res.status()).toBe(200)
      const body = await res.json()
      expect(body.valid).toBe(false)
    }
  })

  test('公开核验响应不含 org/owner 隐私字段', async ({ request }) => {
    const res = await request.get('/v1/evidence/1/verify')
    const body = await res.json().catch(() => ({}))
    expect(JSON.stringify(body)).not.toMatch(/phone|idCard|address|ownerName/i)
  })
})
