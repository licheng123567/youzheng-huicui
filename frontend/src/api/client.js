import createClient from 'openapi-fetch';
/**
 * 契约类型化 HTTP 客户端：类型来自 ../docs/api/openapi-core.yaml 生成的 schema.d.ts。
 * 用契约外的 path/字段 → TypeScript 直接报错（Gate2 防漂移）。
 * baseUrl=/v1，dev 由 vite 代理到后端 9091（或 Prism mock 4010）。
 */
export const api = createClient({ baseUrl: '/v1' });
// 鉴权中间件：自动注入 Bearer 令牌（来自登录后存储）。
api.use({
    onRequest({ request }) {
        const token = localStorage.getItem('token');
        if (token)
            request.headers.set('Authorization', `Bearer ${token}`);
        return request;
    },
});
