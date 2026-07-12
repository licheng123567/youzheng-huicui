// 系统配置的「人话」字典（v1.23.0）。
// 用户原话：「系统配置·业务规则这些配置内容过于开发向，需要中文显示说明。AI 配置逻辑也是一样。」
// 此前页面把 JSON 原样 dump 出来（{"holdCap":50}、{"cooldownMinutes":30}），域名也是 TIMERS/ROTATION 这种码——
// 只有写这段代码的人看得懂。这里把每个键翻成「中文名 + 单位 + 一句话说明它到底管什么」。
//
// 新增配置键时必须同步登记到这里，否则页面会回落成裸键名（fallbackLabel）——那正是我们要消灭的东西。

export type FieldMeta = {
  label: string          // 中文名
  unit?: string          // 单位（条/小时/件…）
  desc: string           // 一句话说明：这个值管什么、调大调小会发生什么
}

export const DOMAIN_META: Record<string, { label: string; desc: string }> = {
  TIMERS: {
    label: '时效参数',
    desc: '各环节的超时时限。到点触发预警或自动回收案件；改动只对**新计时**的案件生效，不会追溯已在计时的案件（BR-M3-19）。',
  },
  ROTATION: {
    label: '轮转配置',
    desc: '催收员手里最多能同时握多少案件、一个案件最多被转手几次。防止案件囤在一个人手上不动。',
  },
  MARK_CODES: {
    label: '通话结果标记码',
    desc: '催收员打完电话选的结果选项（接通有效/空号/拒接…）。「接通有效」会重置该案件的无跟进倒计时。',
  },
  CLOSE_REASONS: {
    label: '结案原因',
    desc: '案件结案时可选的原因清单。类型只有两种：撤案 / 坏账（契约 CloseKindEnum）。',
  },
  SMS: {
    label: '短信默认参数',
    desc: '平台级默认值。物业组织若在【短信通道】单独配置了签名/冷却，以组织的配置为准，这里只作兜底。',
  },
  AI: {
    label: 'AI 配置',
    desc: '话术飞轮的模型与提示词。注意：LLM/ASR 客户端尚未接入（Phase 3），目前只有「飞轮触发条件」真正生效。',
  },
}

export const FIELD_META: Record<string, FieldMeta> = {
  // ── TIMERS ──
  t1Hours: { label: '待派单时限', unit: '小时', desc: '批次导入后多久还没派出去，就给平台报预警（T1）。' },
  t1Seconds: { label: '待派单时限', unit: '秒', desc: '批次导入后多久还没派出去，就给平台报预警（T1）。' },
  t2Hours: { label: '服务商处置时限', unit: '小时', desc: '案件派给服务商后多久没人接手，就提醒服务商并预警临近退回（T2）。' },
  t2Seconds: { label: '服务商处置时限', unit: '秒', desc: '案件派给服务商后多久没人接手，就提醒服务商并预警临近退回（T2）。' },
  tCollectorHours: { label: '无跟进自动释放', unit: '小时', desc: '催收员持有案件后多久没有任何跟进动作，案件自动回流公海（TC）。标记「接通有效」会重置这个倒计时。' },
  tcSeconds: { label: '无跟进自动释放', unit: '秒', desc: '催收员持有案件后多久没有任何跟进动作，案件自动回流公海（TC）。标记「接通有效」会重置这个倒计时。' },
  maxCycleDays: { label: '最长催收周期', unit: '天', desc: '一个案件从派单到结案的最长时限，超时进入超期名单。' },

  // ── ROTATION ──
  holdCap: { label: '单人持有上限', unit: '件', desc: '一个催收员同时最多能持有多少案件。达到上限后无法再抢单/被指派。' },
  maxRotations: { label: '最大轮转次数', unit: '次', desc: '同一案件最多可以被转手几次；超过后不再进入公海，转由服务商负责人处理。' },

  // ── SMS（平台默认值）──
  signature: { label: '默认短信签名', desc: '未在【短信通道】单独配置签名的物业，发短信时用这个签名。签名须已向运营商报备。' },
  cooldownMinutes: { label: '同案短信冷却', unit: '分钟', desc: '同一个案件两条短信之间的最小间隔，防止对同一业主轰炸。' },
  cooldownSeconds: { label: '同案短信冷却', unit: '秒', desc: '同一个案件两条短信之间的最小间隔，防止对同一业主轰炸。' },
  payLinkTtlSeconds: { label: '缴费链接有效期', unit: '秒', desc: '发给业主的缴费链接多久过期。' },
  warnThreshold: { label: '短信余额预警线', unit: '条', desc: '短信余额低于此值时提醒充值；留空=不预警。' },
  templates: { label: '短信模板', desc: '已迁至【短信通道】按组织维护（平台代报备），此处不再使用。' },

  // ── AI ──
  provider: { label: '服务商', desc: '模型提供方。' },
  model: { label: '模型', desc: '调用的具体模型名。' },
  temperature: { label: '温度', desc: '取值越大回答越发散，越小越保守稳定。' },
  maxTokens: { label: '单次最大长度', unit: 'token', desc: '一次生成的最大长度上限。' },
  hotwords: { label: '热词', desc: '转写时优先识别的专业词（物业费/滞纳金/分期…），显著降低这些词的错字率。' },
  preCall: { label: '通话前提示词', desc: '生成「打这通电话该怎么说」的策略建议。' },
  postReview: { label: '通话后提示词', desc: '生成通话复盘小结与改进建议。' },
  riskRules: { label: '风险检测规则', desc: '判定录音里是否出现违规话术（辱骂/威胁/不当承诺）。' },
  trigger: { label: '变体晋升条件', desc: '话术变体满足该条件即自动晋升为正式话术（如「使用≥5次 且 效果提升≥2%」）。这是 AI 配置里目前**唯一真正生效**的一项。' },
  autoIterate: { label: '自动迭代', desc: '是否允许飞轮自动产出话术变体。' },
  liveHint: { label: '通话中实时提示', desc: '通话过程中实时给催收员弹话术提示（依赖 LLM，未接入）。' },
  adoptMode: { label: '采纳方式', desc: 'FORCE_MANUAL=AI 只产草稿，必须人工采纳才能发布——这是护城河的关键，别改成全自动。' },
}

/** 未登记的键回落成裸键名——出现即说明该键没登记到字典，应补齐。 */
export function fieldLabel(key: string): string {
  return FIELD_META[key]?.label ?? key
}

export function fieldDesc(key: string): string {
  return FIELD_META[key]?.desc ?? ''
}

/** 值的人话化：布尔→是/否，数组→逐项，对象→折叠，其余原样 + 单位后缀。 */
export function fieldValue(key: string, v: any): string {
  if (v == null || v === '') return '—'
  const unit = FIELD_META[key]?.unit ?? ''
  if (typeof v === 'boolean') return v ? '是' : '否'
  if (Array.isArray(v)) return v.length ? v.map((x) => (typeof x === 'object' ? (x.label || x.code || JSON.stringify(x)) : String(x))).join('、') : '（空）'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v) + (unit ? ' ' + unit : '')
}
