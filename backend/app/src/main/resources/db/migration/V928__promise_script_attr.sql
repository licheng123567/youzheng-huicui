-- 承诺话术归因(飞轮环3):哪条话术促成了本次承诺,是回流重算的归因锚点。
-- 来自通话前推荐/复盘建议采纳(sourceSuggestionId="script-{N}"),或按 scene 回退归因。
ALTER TABLE promise ADD COLUMN script_id BIGINT;
ALTER TABLE promise ADD CONSTRAINT fk_promise_script
    FOREIGN KEY (script_id) REFERENCES script_lib(id) ON DELETE SET NULL;
CREATE INDEX idx_promise_script_id ON promise (script_id);
COMMENT ON COLUMN promise.script_id IS '促成本次承诺的话术(飞轮环3归因);ON DELETE SET NULL=话术退役不阻塞承诺;nullable=历史/无归因承诺合法';
