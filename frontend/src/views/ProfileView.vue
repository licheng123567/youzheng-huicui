<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuth } from '../stores/auth'
import { api } from '../api/client'

// 个人中心：资料查看(GET /me 已有) + 自助改密(POST /me/password · v1.3.0)。
const auth = useAuth()
const me = computed(() => auth.me)
const dlg = ref(false)
const form = ref({ oldPassword: '', newPassword: '', confirm: '' })

function open() { form.value = { oldPassword: '', newPassword: '', confirm: '' }; dlg.value = true }

// ===== 纯展示辅助（仅 UI 表现层，不参与数据流）=====
// 角色码 → 中文标签（缺省回落原码）
const ROLE_LABEL: Record<string, string> = {
  SA: '平台管理员', SE: '平台员工',
  PA: '物业负责人', PC: '物业协调员',
  CA: '服务商负责人', CC: '催收员',
  ADMIN: '管理员'
}
const roleLabel = computed<string>(() => ROLE_LABEL[me.value?.role ?? ''] ?? (me.value?.role ?? '—'))
// 姓名首字（头像）
const nameInitial = computed<string>(() => { const n = me.value?.name; return n ? String(n).charAt(0) : '我' })
// 组织类型 → .tag 配色
const ORG_TYPE_TAG: Record<string, string> = { PLATFORM: 'pri', PROVIDER: 'suc', PROPERTY: 'war' }
const orgTypeTag = computed<string>(() => ORG_TYPE_TAG[me.value?.org?.type ?? ''] ?? 'inf')
async function submit() {
  if (form.value.newPassword.length < 6) { ElMessage.warning('新密码至少 6 位'); return }
  if (form.value.newPassword !== form.value.confirm) { ElMessage.warning('两次新密码不一致'); return }
  const { error } = await api.POST('/me/password', { body: { oldPassword: form.value.oldPassword, newPassword: form.value.newPassword } as any })
  if (error) { ElMessage.error('改密失败：' + ((error as any)?.message ?? '旧密码错误')); return }
  ElMessage.success('密码已修改'); dlg.value = false
}
</script>

<template>
  <div v-if="me" class="profile-page">
    <!-- 个人资料 -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>个人资料</div>
      </div>
      <div class="profile-hd">
        <div class="av portrait-av" style="background:var(--primary)">{{ nameInitial }}</div>
        <div class="profile-id">
          <div class="nm">{{ me.name }}</div>
          <div class="sub">账号 ID：{{ me.accountId }}</div>
        </div>
        <div class="profile-tags">
          <span class="tag pri">{{ roleLabel }}</span>
          <span v-if="me.org?.name" class="tag" :class="orgTypeTag">{{ me.org.name }}</span>
        </div>
      </div>

      <div class="sec-title">基本信息</div>
      <div class="desc">
        <div class="r"><div class="k">账号 ID</div><div class="v">{{ me.accountId }}</div></div>
        <div class="r"><div class="k">姓名</div><div class="v">{{ me.name }}</div></div>
        <div class="r"><div class="k">角色</div><div class="v"><span class="tag pri">{{ roleLabel }}</span></div></div>
        <div class="r"><div class="k">组织</div><div class="v"><span v-if="me.org?.name" class="tag" :class="orgTypeTag">{{ me.org.name }}</span><span v-else>—</span></div></div>
        <div class="r"><div class="k">数据范围</div><div class="v">{{ me.dataScope ? JSON.stringify(me.dataScope) : 'platform 全量' }}</div></div>
        <div class="r"><div class="k">权限点</div><div class="v"><span v-for="p in me.permissions" :key="p" class="tag inf" style="margin:2px 4px 2px 0">{{ p }}</span></div></div>
      </div>
    </div>

    <!-- 账号与安全 -->
    <div class="card">
      <div class="card-h">
        <div class="t"><span class="bar"></span>账号与安全</div>
        <div class="ops">
          <button class="btn sm" @click="open">修改密码</button>
        </div>
      </div>
      <div class="desc">
        <div class="r"><div class="k">登录密码</div><div class="v">已设置 · 可自助修改（校验旧密码）<a class="btn txt" @click="open">修改密码</a></div></div>
        <div class="r"><div class="k">绑定手机</div><div class="v">手机改绑由管理员 / 负责人交接（BR-M1-02）</div></div>
      </div>
      <div class="note">手机标识（登录名）不变，换人靠改绑手机 + 重置密码交接同一账号。</div>
    </div>

    <!-- 改密弹窗：含校验，保留 Element Plus 原样 -->
    <el-dialog v-model="dlg" title="修改密码（自助 · 校验旧密码）" width="400px">
      <el-form label-width="90px">
        <el-form-item label="旧密码"><el-input v-model="form.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" show-password placeholder="至少 6 位" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="form.confirm" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="submit">确认修改</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.profile-page { max-width: 880px; }
.profile-hd { display: flex; align-items: center; gap: 14px; margin-bottom: 6px; }
.profile-hd .av { width: 46px; height: 46px; font-size: 18px; font-weight: 600; }
.profile-id .nm { font-size: 16px; font-weight: 600; color: var(--txt); }
.profile-id .sub { font-size: 12px; color: var(--sec); margin-top: 3px; }
.profile-tags { margin-left: auto; display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; }
.profile-page .desc .v .btn.txt { margin-left: 10px; }
</style>
