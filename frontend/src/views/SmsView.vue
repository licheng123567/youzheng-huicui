<script setup lang="ts">
// 「短信通道」（v1.21.0·按组织管理）：
//   平台(SA/SE)：**先看组织列表**（一行一个物业：签名/模板数/本月发送/短信余额）→ 点进 /sms/:orgId
//     看该物业的配置、模板与发送记录。签名与模板由平台统一配置（settings.manage → 仅 SA 可编辑；SE 只读）。
//   物业(PL)：本页直接渲染自己的详情（range scope 天然裁剪），配置只读。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useAuth } from '../stores/auth'
import SmsOrgDetail from '../components/SmsOrgDetail.vue'

const auth = useAuth()
const router = useRouter()
const isPlatform = computed(() => auth.me?.role === 'SA' || auth.me?.role === 'SE')

const rows = ref<any[]>([]); const loading = ref(false)
async function loadOrgs() {
  loading.value = true
  const { data, error } = await api.GET('/sms/orgs', { params: { query: { page: 1, size: 200 } } as any })
  loading.value = false
  if (error) { ElMessage.error('加载短信通道组织失败'); rows.value = []; return }
  rows.value = (data as any)?.items ?? []
}
function openOrg(row: any) { router.push(`/sms/${row.orgId}`) }

onMounted(() => { if (isPlatform.value) loadOrgs() })
</script>

<template>
  <div class="card">
    <div class="card-h">
      <div class="t"><span class="bar"></span>短信通道</div>
      <div class="ops">
        <span class="note" style="margin:0">签名与模板由平台统一配置并代向运营商报备；短信按条计费（走短信额度）</span>
      </div>
    </div>

    <!-- 平台：物业组织列表 → 点进详情 -->
    <template v-if="isPlatform">
      <div class="note" style="margin-bottom:10px">点击物业查看其短信配置、模板与发送记录。「平台默认」表示该物业尚未单独配置签名。</div>
      <table v-loading="loading">
        <thead>
          <tr>
            <th>物业组织</th>
            <th style="width:150px">短信签名</th>
            <th style="width:130px">模板</th>
            <th style="width:110px">本月发送</th>
            <th style="width:100px">失败</th>
            <th style="width:120px">短信余额</th>
            <th style="width:90px">通道</th>
            <th style="width:90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.orgId" class="row-click" @click="openOrg(row)">
            <td><b>{{ row.orgName }}</b></td>
            <td>
              <span v-if="row.signName">{{ row.signName }}</span>
              <span v-else class="tag inf">平台默认</span>
            </td>
            <td>
              <span class="tag suc">生效 {{ row.activeTemplates }}</span>
              <span v-if="row.draftTemplates" class="tag war" style="margin-left:4px">待报备 {{ row.draftTemplates }}</span>
            </td>
            <td class="num">{{ row.sentThisMonth }}</td>
            <td class="num"><span :class="row.failedThisMonth ? 'tag dan' : ''">{{ row.failedThisMonth }}</span></td>
            <td class="num">{{ row.smsBalance != null ? row.smsBalance + '条' : '—' }}</td>
            <td><span class="tag" :class="row.enabled ? 'suc' : 'dan'">{{ row.enabled ? '正常' : '停用' }}</span></td>
            <td @click.stop><a class="btn txt" @click="openOrg(row)">明细 ›</a></td>
          </tr>
          <tr v-if="!loading && !rows.length"><td colspan="8" class="note" style="text-align:center;padding:32px 0">暂无物业组织</td></tr>
        </tbody>
      </table>
    </template>

    <!-- 物业：直接看自己 -->
    <SmsOrgDetail v-else :org-id="null" />
  </div>
</template>

<style scoped>
.row-click { cursor: pointer; }
.row-click:hover { background: var(--bg2, #f7f9fc); }
</style>
