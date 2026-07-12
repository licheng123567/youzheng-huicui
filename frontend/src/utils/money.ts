// 金额/比率展示（全栈口径：金额=分 cents，比率=分数 0-1 不×100）。
// 各旧页有内联同名实现（SettlementView/BatchDetailView 等），新页一律从这里引，旧页随改随迁。
export const yuan = (c?: number | null): string =>
  c == null ? '—' : '¥' + (c / 100).toLocaleString('zh-CN')

export const pct = (r?: number | null): string =>
  r == null ? '—' : (r * 100).toFixed(2) + '%'
