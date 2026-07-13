-- V938 资金正确性加固
--
-- 上线评估里【会丢钱】的那一批。这些不是"代码里少判了一下"，而是**数据库层根本没有约束**——
-- 只要有并发或重试，钱就能被凭空造出来，而且所有下游报表会忠实地把错误放大。
-- 因此修复必须落在 DB 层：应用层的 if 判断挡不住两个并发事务同时通过检查。

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 回款幂等：repay_line.idem_key + 部分唯一索引
--
-- 此前 repay_line **除了主键和外键没有任何唯一约束**，创建走的是裸 INSERT、案件上不加锁。
-- 协调员双击「标注回款」、或客户端超时重试，同一笔 5000 元就会插两次：
-- 案件被误判 SETTLED、向物业多收一笔收佣、向服务商多付一笔付佣、催收员多拿一笔提成。**钱真的出去了。**
--
-- 为什么不能用「业务字段唯一」来去重：同一案件同一天收两笔金额相同的现金是**合法**的。
-- UNIQUE(case_id, amount_cents, paid_at, channel) 会误杀真实业务。
-- 正解是幂等键——契约本来就为该端点声明了 Idempotency-Key（"同 key 重复请求返回首次结果"），
-- 只是后端从没真正实现过（见 idempotency_record 注释）。
ALTER TABLE repay_line ADD COLUMN IF NOT EXISTS idem_key TEXT;

COMMENT ON COLUMN repay_line.idem_key IS
    '回款幂等键(客户端 Idempotency-Key)。同键重复提交返回首次那一笔，不再造出第二笔钱。';

-- 部分唯一索引：只约束带键的行。历史数据 idem_key 为 NULL，不受影响。
CREATE UNIQUE INDEX IF NOT EXISTS uq_repay_line_idem_key
    ON repay_line (idem_key) WHERE idem_key IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) 同一笔回款不得给催收员付两次佣金
--
-- co_pay_doc_line 的主键是 (co_pay_doc_id, repay_line_id) —— 它只阻止"同一张单里出现两次"，
-- **不阻止同一个 repay_line_id 出现在两张不同的单里**。
-- 而 co_pay_doc 是全仓唯一把佣金**物化存储**（而非按需推导）的地方，一旦重复，冲正也无法自愈。
--
-- 澄清（勿据此误判严重性）：走 API 是进不来的 —— createCoPayDoc 先对 repay_line 行
-- `FOR UPDATE` 加锁、再查 isLineInAnyCoPayDoc，锁把并发串行化了。
-- 这条约束是**DB 层兜底**：挡住绕过控制器的直接写入（DevSeeder 就这么干过，见下），
-- 也挡住将来有人新写一条没加锁的路径。资金唯一被物化存储的地方，值得一条硬约束。

-- 加约束前先把话说清楚：真有重复就**明确报错**，绝不静默删除任何一行付款记录。
DO $$
DECLARE dup_count INT;
BEGIN
    SELECT count(*) INTO dup_count FROM (
        SELECT repay_line_id FROM co_pay_doc_line GROUP BY repay_line_id HAVING count(*) > 1
    ) x;
    IF dup_count > 0 THEN
        RAISE EXCEPTION E'存在 % 笔回款被纳入了多张催收员佣金单，无法建立唯一约束。\n'
            '这意味着同一笔回款可能被重复计过佣金，必须先人工核对再迁移（本迁移不会替你删任何付款记录）。\n'
            '排查：SELECT repay_line_id, array_agg(co_pay_doc_id) FROM co_pay_doc_line '
            'GROUP BY repay_line_id HAVING count(*) > 1;', dup_count;
    END IF;
END $$;

ALTER TABLE co_pay_doc_line
    ADD CONSTRAINT uq_co_pay_doc_line_repay UNIQUE (repay_line_id);

COMMENT ON CONSTRAINT uq_co_pay_doc_line_repay ON co_pay_doc_line IS
    '一笔回款只能进一张催收员佣金单。缺此约束时同一笔回款可被付两次佣金，且因佣金是物化存储而无法自愈。';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) 佣金倒挂：收佣比例不得低于付佣比例
--
-- 平台向物业收 comm_in_rate、向服务商付 pay_out_rate。收 < 付 = **平台在该批次的每一笔回款上都亏钱**。
-- 应用层唯一的护栏是「comm_in_confirmed=true → 拒改」，可 `comm_in_confirmed` 全仓只有一处置位
-- （DispatchM3Controller 定双佣那条路径），而**派单写 pay_out_rate 的那条路径不置它**。
-- 于是一次正常派单之后标志仍是 false，物业可以事后把收佣比例降到已生效的付佣比例之下，
-- 而对账页会照常返回负毛利、不告警。
--
-- NULL 是合法的（比例尚未提案/尚未定），只有两者都有值时才比较。
ALTER TABLE batch
    ADD CONSTRAINT chk_batch_commission_not_inverted
    CHECK (comm_in_rate IS NULL OR pay_out_rate IS NULL OR comm_in_rate >= pay_out_rate);

COMMENT ON CONSTRAINT chk_batch_commission_not_inverted ON batch IS
    '收佣比例 ≥ 付佣比例。倒挂 = 平台每笔回款都亏钱；DB 层兜底，因为应用层的 comm_in_confirmed 闸在派单路径上不生效。';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) 幂等记录表：把"幂等"从内存 Map 变成真的
--
-- 原实现是 `ConcurrentHashMap`（IdempotencyInterceptor），三重致命：
--   · **跨事务边界的 check-then-act**：preHandle 里查、afterCompletion（**提交之后**）才登记。
--     两个并发的同 key 请求都能通过检查、都能提交 —— 单实例下就已经不成立。
--   · 单 JVM 内存：多副本（哪怕只是滚动发布期间新旧 pod 并存）直接双写。这条**堵死水平扩容**。
--   · 无 TTL：堆无限增长。
--
-- 现在改成 DB 表 + 唯一约束：**插入本身就是那把锁**。并发同 key 时，第二个 INSERT 撞唯一约束，
-- 由数据库来裁决谁先谁后，而不是靠两次读之间的运气。
-- 这是一张**纯占位表**：只回答"这个键被谁抢到了"，不缓存响应体。
-- （回款端点的"重放返回首次结果"由 repay_line.idem_key 直接给出那一行，不需要响应快照；
--   其余端点维持"重放 → 409"的既有语义，只是把裁决从内存挪到了 DB。
--   不做用不上的响应快照列，免得留一堆没人写的死字段。）
CREATE TABLE idempotency_record (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idem_key     TEXT        NOT NULL,
    method       TEXT        NOT NULL,
    path         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_key UNIQUE (idem_key, method, path)
);

COMMENT ON TABLE idempotency_record IS
    '幂等键落库（取代原内存 Map）。唯一约束即锁：并发同键时第二个 INSERT 直接撞约束，不靠两次读之间的运气。失败的请求会删掉自己的键，客户端可用同键安全重试。';

-- 清理用：老记录按 created_at 过期（定时任务 IdempotencyService.purgeExpired）
CREATE INDEX IF NOT EXISTS ix_idempotency_created_at ON idempotency_record (created_at);
