import { computed } from 'vue'
import { useAuth } from '../stores/auth'

/**
 * 资金双线·按 viewRole 判定费率列/项是否整列(整项)渲染(H-03/BR-M9-11)。
 *
 * 语义：服务端已字段级省略——服务商视角的响应物理无 commInRate、物业视角物理无
 * payOutRate。前端据此「整列不渲染」，绝不以占位串(如 '—（XX视角不可见）')展示列名，
 * 否则泄露字段存在性。
 *
 * 判据优先用当前主体角色(me.role)而非数据(items.some)，避免空列表时平台视角误丢列；
 * 同时保留按字段存在性的兜底(fieldPresent)，与「字段缺失即不存在」语义一致。
 *
 * 角色↔可见性：
 *   收佣比例 commInRate：平台(SA/SE) + 物业(PL/PC) 可见；服务商(VL/CO) 无。
 *   付佣比例 payOutRate：平台(SA/SE) + 服务商(VL/CO) 可见；物业(PL/PC) 无。
 */
export function useRoleFields() {
  const auth = useAuth()
  const role = computed(() => auth.me?.role ?? null)

  // 平台双线全含
  const isPlatform = computed(() => role.value === 'SA' || role.value === 'SE')
  // 物业侧：见收佣、不见付佣
  const isProperty = computed(() => role.value === 'PL' || role.value === 'PC')
  // 服务商侧：见付佣、不见收佣
  const isProvider = computed(() => role.value === 'VL' || role.value === 'CO')

  // 收佣比例列可见(平台或物业)
  const showCommInRate = computed(() => isPlatform.value || isProperty.value)
  // 付佣比例列可见(平台或服务商)
  const showPayOutRate = computed(() => isPlatform.value || isProvider.value)

  // 行内真值费率→百分比展示(真值 null=未设费率，与「视角不可见」不同；整列已被裁，此处只处理真值边界)
  function ratePct(r?: number | null): string {
    return r == null ? '—' : (r * 100).toFixed(2) + '%'
  }

  return { role, isPlatform, isProperty, isProvider, showCommInRate, showPayOutRate, ratePct }
}
