// 角色文案单一真源（L-02）：六角色码 ↔ 中文标签，统一引用消除分散硬编码。
// 标签对齐 docs/ui/02-角色操作权限矩阵.md:11-19 与后端 MemberM1Controller.java：
//   SA 平台超管 / SE 平台员工 / PL 物业负责人 / PC 物业协调员 / VL 服务商负责人 / CO 催收员。
// 类型锚定契约枚举 RoleTemplateEnum（schema.d.ts），新增/改码时此处与契约同步。
import type { components } from '../api/schema'

export type RoleCode = components['schemas']['RoleTemplateEnum']

// 角色码 → 「码 中文名」展示标签（下拉/表格列共用）
export const ROLE_LABELS: Record<RoleCode, string> = {
  SA: 'SA 平台超管',
  SE: 'SE 平台员工',
  PL: 'PL 物业负责人',
  PC: 'PC 物业协调员',
  VL: 'VL 服务商负责人',
  CO: 'CO 催收员'
}

// 仅中文名（无码前缀），按 role 角色模板提示等场景使用
export const ROLE_NAMES: Record<RoleCode, string> = {
  SA: '平台超管',
  SE: '平台员工',
  PL: '物业负责人',
  PC: '物业协调员',
  VL: '服务商负责人',
  CO: '催收员'
}

// 安全取标签：未知码兜底回显裸码，避免空白
export function roleLabel(code: string): string {
  return ROLE_LABELS[code as RoleCode] || code
}

// role 角色模板提示：新建成员时按所选角色给出语义说明（净增）
export const ROLE_HINTS: Record<RoleCode, string> = {
  SA: '平台超管：平台侧全量管理权限。',
  SE: '平台员工：平台执行岗，受数据范围限制。',
  PL: '物业负责人：本物业组织负责人，常驻不可降权。',
  PC: '物业协调员：物业侧催收协调，权限须在负责人持有集内裁剪。',
  VL: '服务商负责人：本服务商组织负责人，常驻不可降权。',
  CO: '催收员：服务商侧一线催收，权限须在负责人持有集内裁剪。'
}

export function roleHint(code: string): string {
  return ROLE_HINTS[code as RoleCode] || ''
}
