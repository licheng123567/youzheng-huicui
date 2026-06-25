import { defineStore } from 'pinia';
import { api } from '../api/client';
/** 鉴权状态：登录(契约 /auth/login)→存 token→拉 /me(契约 getMe)。 */
export const useAuth = defineStore('auth', {
    state: () => ({
        token: localStorage.getItem('token') || '',
        me: null,
    }),
    getters: {
        isAuthed: (s) => !!s.token,
        permissions: (s) => s.me?.permissions ?? [],
    },
    actions: {
        async login(username, password) {
            // 契约 /auth/login 请求体为 oneOf(LoginByPassword|LoginBySms)；此处密码登录分支。
            const { data, error } = await api.POST('/auth/login', {
                body: { mode: 'password', username, password },
            });
            if (error || !data)
                throw new Error('登录失败：用户名或口令错误');
            // 后端地基期返回 {token,...}；M1 完整 LoginResult 后此处按契约取 token。
            this.token = data.token ?? '';
            if (!this.token)
                throw new Error('未获取到令牌');
            localStorage.setItem('token', this.token);
            await this.fetchMe();
        },
        async fetchMe() {
            const { data, error } = await api.GET('/me');
            if (error || !data) {
                this.logout();
                throw new Error('获取当前主体失败');
            }
            this.me = data;
        },
        logout() {
            this.token = '';
            this.me = null;
            localStorage.removeItem('token');
        },
        has(permission) {
            return this.permissions.includes(permission);
        },
    },
});
