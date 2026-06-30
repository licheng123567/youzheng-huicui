// 枚举 code → 中文(仅前端展示,便于理解;提交后端/比较仍用原 code)。
// 全量来自契约 schema.d.ts 的 *Enum + 权限矩阵数据范围。各视图按字段所属枚举调用对应 *Label;
// 未命中一律回退原 code(不空白)。展示处建议同时 :title=原code 便于排查。

const M = {
  ActivityType: { CALL: '通话', NOTE: '跟进', TICKET: '工单', SMS: '短信', EVIDENCE: '存证', PROMISE: '承诺', LEGAL: '法务', STATUS: '状态变更', OPLOG: '操作日志' },
  BillingType: { STT: '语音转写', SMS: '短信', EVIDENCE: '存证', LEGAL: '法务' },
  CallRecStatus: { NO_FILE: '无录音', UPLOADING: '上传中', PARSING: '解析中', READY: '已就绪', FAILED: '失败', QUOTA_BLOCKED: '余额不足' },
  CaseStatus: { PENDING: '待派单', PENDING_DISPATCH: '待派单', DISPATCHED: '已派单', PROVIDER_SEA: '服务商公海', IN_PROGRESS: '催收中', PROMISED: '已承诺', SETTLED: '已结清', WITHDRAWN: '已撤回', BAD_DEBT: '坏账', VOIDED: '已作废' },
  Channel: { WECHAT_QR: '微信收款码', BANK_TRANSFER: '对公转账', CASH: '现金' },
  CloseKind: { WITHDRAWN: '撤案', BAD_DEBT: '坏账' },
  CoPayDocStatus: { PENDING_PAY: '待支付', SETTLED: '已结' },
  DispatchMode: { WHOLE: '整批派', SPLIT: '拆单派' },
  DisposeTaskStatus: { PENDING: '待处理', DONE: '已处理' },
  EvidenceScene: { DELIVERY: '送达', RECORDING: '录音', MATERIAL_PACK: '材料打包' },
  EvidenceStatus: { ISSUING: '出证中', ISSUED: '已出证', FAILED: '失败' },
  LegalDocStatus: { GENERATING: '生成中', GENERATED: '已生成', DELIVERED: '已送达', SIGNED: '已签收', ARCHIVED: '已归档' },
  LegalDocType: { COLLECTION_LETTER: '催收单', LAWYER_LETTER: '律师函', LITIGATION: '诉讼文件' },
  LegalStage: { NONE: '无', FUNCTION_LETTER: '职能告知函', LAWYER_LETTER: '律师函', LITIGATION: '诉讼', DELIVERED: '已送达' },
  OrgType: { PLATFORM: '平台', PROPERTY: '物业', PROVIDER: '服务商' },
  PayLinkStatus: { ACTIVE: '有效', EXPIRED: '已失效' },
  PaymentRequestStatus: { PENDING: '待付款', PAID: '已付款', VOIDED: '已作废' },
  PlaybookAdoptMode: { FORCE_MANUAL: '强制人工', LOW_RISK_AUTO: '低风险自动' },
  Pool: { PLATFORM_SEA: '平台公海', PROVIDER_SEA: '服务商公海', OPEN_POOL: '开放抢单池', PRIVATE: '私海' },
  PromiseState: { PENDING: '待兑现', FULFILLED: '已兑现', PARTIAL_FULFILLED: '部分兑现', BROKEN: '已违约' },
  RechargeType: { STT: '语音转写', SMS: '短信' },
  ReconSide: { IN: '收佣', OUT: '付佣' },
  ReduceDecide: { COLLECTOR_SELF: '催收员自决', OFFLINE_INTERNAL: '线下内部', PL_APPROVE: '物业负责人审批' },
  ReduceState: { EFFECTIVE: '生效', OFFLINE_TRACE: '线下留痕' },
  RiskLevel: { HIGH: '高', MID: '中', LOW: '低' },
  RiskReviewVerdict: { CONFIRMED: '确认', FALSE_POSITIVE: '误报', ESCALATED: '已升级' },
  RoleTemplate: { SA: '平台超管', SE: '平台员工', PL: '物业负责人', PC: '物业协调员', VL: '服务商负责人', CO: '催收员' },
  ScriptSource: { AI_MINED: 'AI 挖掘', EXPERT: '专家', EXPER: '专家' },
  ScriptStatus: { EFFECTIVE: '现行', CANDIDATE: '候选', RETIRED: '已退役' },
  SettingsDomain: { TIMERS: '时效参数', ROTATION: '轮转', MARK_CODES: '标记码', CLOSE_REASONS: '结案原因', SMS: '短信' },
  SmsSendStatus: { SENT: '已发送', FAILED: '失败', DELIVERED: '已送达' },
  StrategyCardAction: { PROMISE: '登记承诺', TICKET: '转工单', PAYLINK: '发缴费链接', FOLLOWUP: '写跟进', NONE: '仅知悉' },
  SupervisionAction: { REMIND: '提醒', TALK: '督导谈话', TRAINING: '安排培训', NOTE: '记录' },
  TicketStatus: { PENDING: '待处理', HANDLED: '已处理' },
  TodoCategory: { PROMISE_DUE: '承诺到期', RELEASE_WARN: '临近释放', TICKET_RECEIPT: '工单回执', NEW_ASSIGNED: '新分配', LEGAL_DELIVERY: '法务待送达', REPAY_MARK: '回款待标', PAYLINK_SEND: '链接待发', REDUCE_APPROVE: '减免待批', T2_RETURN_WARN: '即将退回平台', T1_DISPATCH_WARN: '待派单超时' },
  // 数据范围(权限矩阵 dataScope·非 schema 枚举)
  DataScope: { 'own-org': '本组织', range: '数据范围内', platform: '全平台', 'case-holder': '持有案件', 'case-actor': '经办案件' },
  // 账号/组织状态(契约未单列枚举,统一收录)
  Status: { ACTIVE: '启用', INACTIVE: '停用', DISABLED: '停用', ENABLED: '启用', LOCKED: '锁定' },
} as const

export type EnumGroup = keyof typeof M

/** 按枚举组取中文;未命中回退原 code。 */
export function enumLabel(group: EnumGroup, code?: string | null): string {
  if (code == null || code === '') return '—'
  const g = M[group] as Record<string, string>
  return g[code] ?? code
}

// 常用便捷导出(按字段所属枚举调用)
export const caseStatusLabel = (c?: string | null) => enumLabel('CaseStatus', c)
export const poolLabel = (c?: string | null) => enumLabel('Pool', c)
export const callRecStatusLabel = (c?: string | null) => enumLabel('CallRecStatus', c)
export const promiseStateLabel = (c?: string | null) => enumLabel('PromiseState', c)
export const ticketStatusLabel = (c?: string | null) => enumLabel('TicketStatus', c)
export const legalStageLabel = (c?: string | null) => enumLabel('LegalStage', c)
export const legalDocStatusLabel = (c?: string | null) => enumLabel('LegalDocStatus', c)
export const legalDocTypeLabel = (c?: string | null) => enumLabel('LegalDocType', c)
export const evidenceStatusLabel = (c?: string | null) => enumLabel('EvidenceStatus', c)
export const evidenceSceneLabel = (c?: string | null) => enumLabel('EvidenceScene', c)
export const orgTypeLabel = (c?: string | null) => enumLabel('OrgType', c)
export const statusLabel = (c?: string | null) => enumLabel('Status', c)
export const roleTemplateLabel = (c?: string | null) => enumLabel('RoleTemplate', c)
export const dataScopeLabel = (c?: string | null) => enumLabel('DataScope', c)
export const reconSideLabel = (c?: string | null) => enumLabel('ReconSide', c)
export const channelLabel = (c?: string | null) => enumLabel('Channel', c)
export const payReqStatusLabel = (c?: string | null) => enumLabel('PaymentRequestStatus', c)
export const payLinkStatusLabel = (c?: string | null) => enumLabel('PayLinkStatus', c)
export const coPayDocStatusLabel = (c?: string | null) => enumLabel('CoPayDocStatus', c)
export const billingTypeLabel = (c?: string | null) => enumLabel('BillingType', c)
export const smsSendStatusLabel = (c?: string | null) => enumLabel('SmsSendStatus', c)
export const riskLevelLabel = (c?: string | null) => enumLabel('RiskLevel', c)
export const riskVerdictLabel = (c?: string | null) => enumLabel('RiskReviewVerdict', c)
export const activityTypeLabel = (c?: string | null) => enumLabel('ActivityType', c)
export const todoCategoryLabel = (c?: string | null) => enumLabel('TodoCategory', c)
export const reduceDecideLabel = (c?: string | null) => enumLabel('ReduceDecide', c)
export const reduceStateLabel = (c?: string | null) => enumLabel('ReduceState', c)
export const scriptStatusLabel = (c?: string | null) => enumLabel('ScriptStatus', c)
export const scriptSourceLabel = (c?: string | null) => enumLabel('ScriptSource', c)
export const supervisionActionLabel = (c?: string | null) => enumLabel('SupervisionAction', c)
export const dispatchModeLabel = (c?: string | null) => enumLabel('DispatchMode', c)
export const settingsDomainLabel = (c?: string | null) => enumLabel('SettingsDomain', c)
export const disposeTaskStatusLabel = (c?: string | null) => enumLabel('DisposeTaskStatus', c)
export const strategyCardActionLabel = (c?: string | null) => enumLabel('StrategyCardAction', c)
