backend/db/migration/V1__core_schema.sql:710:    CONSTRAINT fk_risk_provider    FOREIGN KEY (provider_id)  REFERENCES org(id)             ON DELETE RESTRICT,
backend/db/migration/V1__core_schema.sql:724:CREATE INDEX idx_risk_provider_id   ON risk_record (provider_id);
backend/db/migration/V1__core_schema.sql:738:    status      TEXT        NOT NULL DEFAULT 'PENDING',
backend/db/migration/V1__core_schema.sql:744:    CONSTRAINT chk_dispose_status  CHECK (status IN ('PENDING','DONE'))
./backend/db/migration/V1__core_schema.sql:167:    -- provider_id：承接服务商，派单后才有
./backend/db/migration/V1__core_schema.sql:168:    provider_id         BIGINT,
./backend/db/migration/V1__core_schema.sql:176:    status              TEXT        NOT NULL DEFAULT 'PENDING',
./backend/db/migration/V1__core_schema.sql:181:    CONSTRAINT fk_batch_provider   FOREIGN KEY (provider_id) REFERENCES org(id)      ON DELETE RESTRICT,
./backend/db/migration/V1__core_schema.sql:182:    CONSTRAINT chk_batch_status    CHECK (status IN ('PENDING','DISPATCHED','IN_PROGRESS','CLOSED')),
./backend/db/migration/V1__core_schema.sql:193:CREATE INDEX idx_batch_provider_id  ON batch (provider_id);
./backend/db/migration/V1__core_schema.sql:224:    status                  TEXT        NOT NULL DEFAULT 'PENDING_DISPATCH',
./backend/db/migration/V1__core_schema.sql:243:        'PENDING_DISPATCH','PROVIDER_SEA','IN_PROGRESS','PROMISED',
./backend/db/migration/V1__core_schema.sql:258:COMMENT ON TABLE  "case"                    IS '案件。VOIDED=误传纠错作废（仅PENDING_DISPATCH可作废 BR-M2-17）。BIZ_DUP_ACCT防重';
./backend/db/migration/V1__core_schema.sql:399:    state       TEXT        NOT NULL DEFAULT 'PENDING',
./backend/db/migration/V1__core_schema.sql:405:    CONSTRAINT chk_promise_state    CHECK (state IN ('PENDING','FULFILLED','PARTIAL_FULFILLED','BROKEN'))
./backend/db/migration/V1__core_schema.sql:409:COMMENT ON COLUMN promise.state      IS 'PromiseState: PENDING=待履约 FULFILLED=已兑现 BROKEN=已爽约';
./backend/db/migration/V1__core_schema.sql:423:    state       TEXT        NOT NULL DEFAULT 'PENDING',
./backend/db/migration/V1__core_schema.sql:427:    CONSTRAINT chk_installment_state  CHECK (state IN ('PENDING','FULFILLED','BROKEN')),
./backend/db/migration/V1__core_schema.sql:446:    status      TEXT        NOT NULL DEFAULT 'PENDING',
./backend/db/migration/V1__core_schema.sql:457:    CONSTRAINT chk_ticket_status     CHECK (status IN ('PENDING','HANDLED'))
./backend/db/migration/V1__core_schema.sql:461:COMMENT ON COLUMN ticket.status     IS 'TicketStatus: PENDING=待处理 HANDLED=已回执';
