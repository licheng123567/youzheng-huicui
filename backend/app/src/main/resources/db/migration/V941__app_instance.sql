-- V941 实例心跳表（单实例护栏）
--
-- 登录票据、短信验证码、发码冷却这三样目前都存在**进程内存**里（AuthController 的三个
-- ConcurrentHashMap）。单实例下完全正确；一旦跑起第二个副本（哪怕只是滚动发布期间新旧容器并存），
-- 失败方式是**静默**的：
--   · 用户在 A 实例拿到验证码、请求打到 B 实例 → 「验证码不存在」，而他明明刚收到；
--   · 多账号用户选账号登录会随机失败；
--   · 发码冷却按实例各算一份 → 冷却形同虚设，而短信是**要花钱**的。
-- 这些都不会在日志里留痕，你只会收到「系统时好时坏」的投诉。
--
-- 所以宁可起不来，也不要静默地半坏：SingleInstanceGuard 靠这张表检测「同库上是否已有活实例」。
-- 真要多实例，先把上面三样搬到 Redis。
CREATE TABLE app_instance (
    instance_id TEXT        PRIMARY KEY,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE app_instance IS
    '后端实例心跳。心跳超过 45s 未更新即视为已死（会被下一个启动的实例清理）。优雅停机时实例会主动摘除自己，故正常升级不会被自己的心跳挡住。';
