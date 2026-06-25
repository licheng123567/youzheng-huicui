import { defineStore } from 'pinia'
import { api } from '../api/client'
import type { components } from '../api/schema'

type Me = components['schemas']['Me']

/** 鉴权状态：登录(契约 /auth/login)→存 token→拉 /me(契约 getMe)。 */
export const useAuth = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    me: null as Me | null,
  }),
  getters: {
    isAuthed: (s) => !!s.token,
    permissions: (s) => s.me?.permissions ?? [],
  },
  actions: {
    async login(username: string, password: string) {
      return this._doLogin({ mode: 'password', username, password })
    },
    async loginSms(phone: string, code: string) {
      return this._doLogin({ mode: 'sms', phone, code })
    },
    // 单账号 → {done:true}(已登录)；多账号 → {done:false, loginTicket, accounts} 待选(BR-M1-11)。
    async _doLogin(body: Record<string, string>): Promise<{ done: boolean; loginTicket?: string; accounts?: any[] }> {
      const { data, error } = await api.POST('/auth/login', { body: body as never })
      if (error || !data) throw new Error('登录失败：凭据错误')
      const d = data as { token?: string; loginTicket?: string; accounts?: any[] }
      if (d.token) { this._setToken(d.token); await this.fetchMe(); return { done: true } }
      if (d.loginTicket) return { done: false, loginTicket: d.loginTicket, accounts: d.accounts ?? [] }
      throw new Error('登录响应异常')
    },
    async requestSmsCode(phone: string) {
      const { error } = await api.POST('/auth/sms-code', { body: { phone } as never })
      if (error) throw new Error('验证码发送失败')
    },
    async selectAccount(loginTicket: string, accountId: string) {
      const { data, error } = await api.POST('/auth/select-account', { body: { loginTicket, accountId } as never })
      const tk = (data as { token?: string })?.token
      if (error || !tk) throw new Error('选择账号失败（票据可能过期，请重新登录）')
      this._setToken(tk); await this.fetchMe()
    },
    _setToken(t: string) { this.token = t; localStorage.setItem('token', t) },
    async fetchMe() {
      const { data, error } = await api.GET('/me')
      if (error || !data) { this.logout(); throw new Error('获取当前主体失败') }
      this.me = data
    },
    logout() {
      this.token = ''
      this.me = null
      localStorage.removeItem('token')
    },
    has(permission: string) {
      return this.permissions.includes(permission)
    },
  },
})
