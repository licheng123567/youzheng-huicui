-- 【横切层·鉴权】account 增口令哈希列（ERD ACCOUNT 未建模口令，此处补；生产应在 ERD/契约同步）。
ALTER TABLE account ADD COLUMN IF NOT EXISTS password_hash TEXT;
-- 哈希值由 DevSeeder 在启动时按 huicui.dev-password 写入（dev），避免在 SQL 里硬编码 BCrypt。
