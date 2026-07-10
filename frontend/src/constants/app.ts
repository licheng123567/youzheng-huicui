// 催收员 App（Android）的分发配置单一真源。
//
// 为什么不写死一个地址：App 走**企业侧载**分发（PRD 11-移动App §4.2 决定不上 Google Play，
// 因为 MANAGE_EXTERNAL_STORAGE 与 READ_CALL_LOG 在通话录音场景下基本必被拒审）。
// 侧载意味着 APK 由部署方自己托管，地址随环境而变。
//
// 约定：把 APK 放到前端静态资源同源的 /app/huicui.apk。
// 需要放别处（对象存储、内网文件服务器）时，构建期设 VITE_APP_DOWNLOAD_URL 覆盖。

/** 安装包下载地址。同源默认值让「前端部署在哪，APK 就在哪」成为最省心的约定。 */
export const APP_DOWNLOAD_URL: string =
  (import.meta.env.VITE_APP_DOWNLOAD_URL as string | undefined)?.trim() ||
  `${window.location.origin}/app/huicui.apk`

/**
 * App 只对**外勤作业角色**开放（BR-APP-01）：催收员（CO）与物业协调员（PC）。
 *
 * 催收员：拨号 → 系统录音 → 自动回传。
 * 物业协调员：同上，外加上门送达的拍照存证。
 *
 * 其它四个角色登录 App 会被门禁拦下，看到「请使用网页端」——**包括物业负责人（PL）**。
 * 他其实有 `case.call` 权限（BR-M4-01a 允许他给关联案件打电话），但他是管理岗，
 * 日常在网页端作业。这是产品规则，不是权限规则，所以按角色判而不是按权限判。
 */
export const APP_ROLES = ['CO', 'PC'] as const

/** 当前登录角色能不能用这个 App。 */
export function canUseApp(role?: string | null): boolean {
  return !!role && (APP_ROLES as readonly string[]).includes(role)
}
