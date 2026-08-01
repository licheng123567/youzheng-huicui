# UAT 全角色业务旅程扫描报告

- 执行日期：2026-07-30 至 2026-07-31（US/Pacific）
- 隔离环境：`huicui-uat`，仅使用可重建的合成数据
- 扫描时线上活跃 SHA：`289c1e7aecf4c5bc2d342b846929c19c62e8ec12`
- 候选修复提交：`a105d54`（成员）、`b11ade0`（批次减免权限）、`5229d0f`（扫描与验收加固）、`6f3a7f1`/`0a015a6`（bare checkout 验收兼容）、`18106cf`（GNU/POSIX chmod 兼容）、`ed96a3f`/`a4aca60`（契约环境与仓库隔离）、`2a9c988`/`b72f355`（登录 API 静默与 trace 关闭）、`72ccc23`（生命周期隔离预算）
- 口令处理：只从服务器环境文件注入进程环境；报告、命令输出和 Playwright trace 均不保存口令或令牌

## 结论

初始完整回归共执行 273 项：261 通过、10 跳过、2 失败。两个失败均由同一个已复现产品缺陷引起：物业负责人（PL）打开 `/members` 时，前端错误请求仅限服务商使用的 `/v1/providers/{id}/collector-capacity`，产生 403。候选代码已按组织类型增加守卫。

代码审查加固后，候选完整回归执行 281 项：271 通过、10 跳过、0 失败。随后新增的 API 静默判断单测 5/5、最终六角色页面与移动端矩阵 90/90、隔离业务生命周期 1/1 均通过。候选扫描还发现并修复了批次详情对无 `reduce.policy.edit` 角色错误请求 `/reduce-tiers` 的 403。

跨角色真实业务生命周期另行隔离执行并通过：平台导入和派单、服务商接单和分配、催收员跟进、物业协调员登记回款、平台按唯一批次核对结算金额。移动端 PC/CO 作业壳和案件详情、SA 移动端门控通过 3/3。

扫描后已重建 UAT 合成数据，独立 `verify.sh` 验证通过。

## 失败分类

| ID | 角色/路径 | 复现与证据 | 分类 | 根因 | 处理 |
| --- | --- | --- | --- | --- | --- |
| F-01 | PL `/members` | `members.spec.ts` 捕获到 `GET /v1/providers/2/collector-capacity`；完整账号和手机号断言已通过，但“不调用服务商接口”断言失败 | `PRODUCT_BUG` | `MembersView.loadSupervise()` 对 PROPERTY 和 PROVIDER 无条件调用服务商容量接口 | 仅当 `org.type === 'PROVIDER'` 时请求；聚焦回归通过 |
| F-02 | PL `/members` 全页面诊断 | 诊断器记录同一请求 403 和浏览器资源加载错误 | `PRODUCT_BUG`（F-01 的重复检测） | 同 F-01 | 候选前端的全页面诊断回归通过 |
| F-03 | SA/VL/PC 批次详情 | `GET /v1/batches/{id}/reduce-tiers` 返回 403 | `PRODUCT_BUG` | 读取端点也要求 `reduce.policy.edit`，前端却对所有可看批次的角色请求 | 请求前按权限门控；PC 直接断言没有发出该请求 |
| T-01 | 多角色禁止菜单断言 | “结算对账”等文字同时可能是当前角色允许菜单名 | `TEST_BUG` | 仅按文字查找会把允许项误判成禁止项 | 禁止项集合排除当前角色允许的同名菜单 |
| T-02 | 快速跨页扫描 | 连续 `goto` 时浏览器会中止上一页尚未完成的请求 | `TEST_BUG` | 页面稳定条件不足 | 每页等待 `networkidle` 后再检查脱敏诊断 |
| T-03 | 成功空响应写请求 | 已收到 `200 + Content-Length: 0` 后 Chromium 报 `ERR_ABORTED` | `TEST_BUG` | 开发代理下空响应不产生 `requestfinished` | 仅对已收到 2xx 且长度为 0 的精确形态忽略；其他中断仍失败 |
| T-04 | 生命周期角色切换 | 主动导航取消旧身份 `/me`；Vite 源模块影响静默判断 | `TEST_BUG` | token 清理顺序和等待范围不正确 | 同源页先清 token；静默等待只跟踪 `/v1` 业务 API |
| T-05 | 部署冒烟登录后跳转 | 组织页冒烟先后捕获仍在飞行的 `GET /v1/workbench` 与 `GET /v1/notifications/unread-count` 被导航取消 | `TEST_BUG` | SPA 的 `networkidle` 可能在 Dashboard/AppLayout 的 `onMounted` 请求登记前返回 | 点击登录前跟踪全部 `/v1/` 请求，要求无在途请求并保持静默窗口后才返回；最终重复 10 轮、20/20 通过 |
| T-06 | 服务器隔离生命周期 | 首次服务器运行在最终回款步骤触发 45 秒用例总超时，清理重置正常完成 | `TEST_BUG` | 登录 API 静默等待与服务器瞬时性能抖动共同消耗固定总预算 | 仅将 `@lifecycle` 总预算提高到 90 秒，不放宽任何断言；同环境完整链路 1/1 通过 |
| E-01 | 移动端项目 | iPhone 13 设备描述符默认选择 WebKit，但 UAT 镜像只安装 Chromium | `ENVIRONMENT` | 浏览器类型被设备默认值覆盖 | 移除 `defaultBrowserType`，保留移动视口并统一 Chromium |
| E-02 | UAT 重置 | 旧版 `reset.sh` 在 Web 容器刚启动时立即验证，偶发 `curl: (56) Recv failure`；随后独立验证通过 | `ENVIRONMENT` | Compose 启动与 HTTP 可用之间存在竞态 | `docker compose up -d --wait --wait-timeout 240`，契约测试覆盖 |
| R-01 | PL 成员工作督导指标 | 产品期望物业协调员的月度/今日产能，但当前契约只有服务商催收员容量接口 | `REQUIREMENT_REVIEW` | 后端没有物业协调员对应的聚合 API，服务商接口又明确禁止 PL | 不伪造数据、不放宽权限；待产品确认统计口径并新增契约 |

## 用户提出的成员数据核验

- 系统管理 → 组织管理：翠湖物业负责人账号 `cuihu_pl`，完整手机号 `13900000001`。
- 系统管理 → 成员管理：翠湖协调员账号 `cuihu_pc`，完整手机号 `13900000006`。
- SA/SE 的成员管理仍只展示平台员工（SA/SE）；服务商与物业成员由各自负责人维护，符合 BR-M1-04a。
- 页面直接断言 11 位完整手机号，不接受掩码、错误字段或组织间串数。

## 执行记录

| 范围 | 结果 | 说明 |
| --- | --- | --- |
| 完整桌面 + 移动回归 | 261 通过、10 跳过、2 失败，耗时约 18.7 分钟 | 273 项，单 worker；2 个失败均为 F-01 |
| 候选完整桌面 + 移动回归 | 271 通过、10 跳过、0 失败，耗时约 8.0 分钟 | 281 项，单 worker；每项自动启用脱敏页面/API 诊断 |
| 候选成员修复定向回归 | 2/2 通过 | 完整账号/手机号、无服务商容量请求、PL 页面诊断干净 |
| 最终诊断安全单测 | 5/5 通过 | 任意查询值脱敏、精确失败白名单、Bearer 不进入 Node 请求上下文 |
| 最终角色与移动补测 | 90/90 通过 | 六角色允许页、禁止直链；PC/CO 移动作业，SA 移动端门控 |
| 跨角色业务生命周期 | 1/1 通过 | 按本次唯一批次编码核对回款 `¥12.34`，测试后重置 UAT |
| 角色菜单/真页面扫描 | 全部通过 | 六角色所有可见菜单逐页打开，所有禁止菜单直链逐角色拦截 |
| UAT 恢复 | 通过 | 扫描后重建合成数据，独立 `verify.sh` PASS |
| 源码容器门禁 | 通过 | 后端 39 项、前端生成/导航/构建、路由 175/175 |
| 部署后契约兼容 | 通过 | 服务器扫描在数据重置前发现 `chmod -x` 的 GNU/POSIX `umask` 差异、静态契约继承真实 `UAT_STATE_DIR` 及 hook 夹具隐式依赖当前 Git 目录；现已改用显式权限/仓库路径并清除外部 UAT 运行时变量，遗留的同 SHA `pending-sha` 已核验后删除 |
| 部署冒烟稳定性 | 20/20 通过 | 通过 SSH 隧道对健康 UAT 单 worker 连续执行 10 轮最终全 API 静默版本；未再出现 workbench、未读消息取消或其他诊断错误 |

10 项跳过均由当前合成种子不具备测试所需的特定状态触发，包括代操作日志、差异漂移、已脱敏 VL/CO 案件、额度阻断和批量拒绝明细；它们不是执行失败，也未被计入通过数。

## 依赖安全基线

`npm audit` 报告 7 个依赖项风险：2 个 moderate、5 个 high、0 critical。

- `vite`/`esbuild`：开发服务器相关问题，彻底升级建议跨主要版本到 Vite 8，需单独兼容性工作。
- `js-yaml`/`@redocly/openapi-core`、`brace-expansion`、`postcss`：存在可用更新，需在独立依赖升级任务中更新锁文件并重跑全部门禁。
- `xlsx`：当前 npm 解析结果无自动修复版本，包含原型污染和 ReDoS 公告；需评估替换或可信输入边界。

本任务未执行 `npm audit fix --force`，避免把未经验证的主要版本升级混入成员缺陷修复。完整 JSON 基线保存在本地忽略目录 `frontend/uat-test-results/npm-audit-baseline.json`。

## 产物

- JSON 结果：`frontend/full-scan-results/results.json`
- HTML 报告：`frontend/full-scan-results/html/index.html`
- 失败截图、视频和脱敏上下文：`frontend/full-scan-results/artifacts/`
- npm 安全基线：`frontend/uat-test-results/npm-audit-baseline.json`
- 部署后服务器扫描产物约定：`/var/log/huicui-uat/full-scan/<UTC时间>-<40位SHA>/`

这些目录不会提交到 Git。全量与部署冒烟配置均关闭 Playwright trace，避免原始请求头、Cookie 或令牌进入产物；此前失败候选生成的单个 trace 已从服务器精确删除。鉴权 API 在浏览器上下文执行，Bearer 不经过 Playwright Node 请求上下文。诊断 JSON 仅记录脱敏后的方法、路径和状态码。

## 部署后验收条件

候选 SHA 部署到 UAT 后，必须运行受确认保护的 `full-scan.sh --confirm huicui-uat`。只有源码门禁、隔离生命周期、完整回归、最终重置和 `verify.sh` 全部通过，才写入 `full-scan-pass-sha` 并视为验收完成。
