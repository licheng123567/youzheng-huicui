# 有证慧催 · Android App

催收员端。当前进度：**M-A1 + M-A2 代码完成**，等待真机验收。

M-A2 = 录音管线：拨号 → 通话结束检测系统录音 → 匹配案件 → 自动上传 → 查解析状态。
详见 [§六 录音管线](#六录音管线m-a2)。**代码完成不等于在你的手机上跑通** —— 见 §五 的诚实边界。

---

## 一、A0 spike 结论：Kotlin 生成器能吃下 3.1 契约（实测）

契约是 OpenAPI **3.1**（115 处 `type: [x,'null']`、22 处 `oneOf`、3 处 `discriminator`），
Spring 生成器早已验证，Kotlin 生成器此前未经验证 —— 这是立项时的头号风险。实测结果：

| 检查项 | 结果 |
|---|---|
| `openapi-generator 7.23.0 -g kotlin --library jvm-retrofit2` 能否生成 | **能**，且**未加** `--skip-validate-spec` |
| 生成物能否编译 | **能**，844 个 class；只需修 **1 处**生成器缺陷（见下） |
| 运行期能否反序列化 `GET /me`（含 `@Contextual` 枚举） | **能**（实测 `role=CO`，`@Contextual` 有 fallback，不需注册） |
| `POST /auth/login` 的 `oneOf` 请求体 | 生成物**不可用**，自写一个 4 方法接口绕开（见下） |

**结论：走「构建期从契约生成」，不落 fallback，不动契约。**

### 1.1 唯一的生成器缺陷（已用断言锁住）

契约 `POST /cases/{id}/recordings` 的 multipart 参数 `source` 是内联枚举 + `default: MANUAL`。
生成器把参数类型出成 `kotlin.String?`，默认值却写成 `Source.MANUAL` —— 那个枚举类**根本没生成**：

```
e: .../apis/CollectionApi.kt:529:203 Unresolved reference 'Source'.
```

`api-client/build.gradle.kts` 的 `generateApiClient` 任务在生成后把它改回字面量 `"MANUAL"`（wire 上本就是这个字符串）。
补丁**不是无脑正则**：它把命中的坏默认值收集成集合，与 `expectedBadDefaults`（当前 = `{Source.MANUAL}`）严格比对，
**不等就构建失败**。契约再冒出同类默认值、或生成器上游修好了，都会立刻暴露，不许悄悄漂移。

### 1.2 三个自写的鉴权端点（`data/net/AuthEdgeApi.kt`）

其余端点一律用生成物。这三个生成器表达不了：

1. **`POST /auth/login`** — 请求体是 `oneOf(LoginByPassword, LoginBySms)` + discriminator `mode`。
   生成器产出一个**零子类的 sealed class `LoginRequest`**，还把两个分支的字段全提成 non-null abstract val
   （`LoginByPassword` 根本没有 `phone`/`code`）：编译得过，永远无法实例化。
   但两个分支类自身是干净的 `@Serializable` data class，`mode` 就是普通字段
   → 直接用两个方法打同一个 path。零自写 DTO，类型安全。
2. **`POST /auth/sms-code`** — 契约 200 无 content，生成物是 `Response<Unit>`，
   拿不到 `ttlSeconds`，429 的 `retryAfterSeconds` 也无处落。重发倒计时必须要这两个值。
3. **`POST /auth/select-account`** — 契约声明 200 返回 `LoginTokenResult{token}`，
   而后端 `AuthController.selectAccount` 实际返回完整 `LoginResult`（含 `mustChangePassword`）。

> **已知契约缺口（待下一次小版本，纯加字段、非破坏性）**：
> `select-account` 的 200 应改为 `LoginResult`。在此之前，多账号 + 首登改密的用户
> 仍会被后端 `JwtAuthFilter` 用 `403 MUST_CHANGE_PASSWORD` 兜住，**不会绕过改密**。

### 1.3 为什么不用 `org.openapi.generator` Gradle 插件

它在 Maven Central 最新只到 **7.14.0**，而本仓库把生成器版本锁在 `openapitools.json` 的 **7.23.0**
（前端与 Spring 桩都用它）。用插件 = App 端悄悄换一个生成器版本 —— 正是仓库反向 `route_coverage` 要防的漂移。
故直接 `JavaExec` 跑同一个 CLI jar，版本**从 `openapitools.json` 读**，单一事实源。

**生成物不入库**（`app-android/**/build/` 已进 `.gitignore`），与仓库「生成物从契约再生」的铁律一致。
契约一改，`:api-client:assemble` 编译即挂 —— 这就是 App 侧的防漂移闸门，CI 里 `docs/api/**` 也会触发本工作流。

---

## 二、M-A2（PR-A2）新增：四屏 + 离线读

- **工作台**：`GET /workbench` 的 KPI + 待办；带 `filterKey` 的 KPI 可点击过滤待办（契约定义的交互）。
- **案件**：两个 Tab。「我的案件」`GET /cases`，**可离线读**；「公海」`GET /sea?pool=provider`，可抢单。
- **案件详情**：只读 + 拨号。跟进/承诺/缴费链接/释放属于后续，**不放灰按钮冒充**。
- **消息**：`GET /notifications`，点开即已读，未读数画在底部 Tab 角标上。

### 实测澄清的三条语义（勿凭直觉改）

| 以为 | 实际 |
|---|---|
| `Case.redacted` = 「你不是持有人」 | = **「案件已关闭」**（SETTLED/WITHDRAWN）。未持有的公海案件 `redacted=false`，但 `contacts` 是空数组 |
| 能否拨号看 `holderId` | 看 `availableActions` 是否含 `call`（实测取值 follow/promise/payLink/call/release/ticket/claim） |
| 公海脱敏要客户端处理 | 后端已经把 `ownerName` 换成 `***` 并给 `contactMasked=true`；`Case` 里**根本没有电话字段** |

### 离线缓存的边界

Room 只缓存**列表级**字段（户号/业主/房号/金额/状态）。联系人电话、时间线、话术**不入库**——
它们是敏感数据，且离线也打不了电话。详情页断网时退回展示缓存的基本信息，并明说「离线」。

「网络失败」只认 `IOException`。**HTTP 4xx/5xx 不读缓存**：服务端明确说了「你没权限」，
再拿旧数据糊弄用户，等于把权限收回这件事悄悄延迟了。

退出登录会清空缓存 —— 一号多账号的手机上，上一个账号的案件不能留给下一个人看。

### 不引 Hilt

M-A2 因 Room 引入了 KSP，但对象图仍只有十来个节点、零多绑定场景，`ServiceLocator` 够用。
Hilt 换来的只是更多构建期活动件。

---

## 三、工程结构

```
app-android/
  settings.gradle.kts          :app + :api-client；foojay 自动拉 JDK 工具链
  gradle/libs.versions.toml    版本目录（唯一改版本的地方）
  api-client/                  契约客户端：构建期生成，产物在 build/，不入库
  app/                         Compose UI + 鉴权 + 网络
```

- `minSdk 26`（PRD OQ-APP-1：读系统录音目录 + `java.time` 需要 26+）、`compileSdk/targetSdk 36`。
- **AGP 8.13.2 + compileSdk 36**：androidx 的最新档（core-ktx 1.19 / lifecycle 2.11）要求 AGP 9.1+ 与 compileSdk 37，
  为此换掉整套构建不划算，故把 androidx 钉在兼容档（core-ktx 1.17 / activity-compose 1.11 / lifecycle 2.9.4）。
- **M-A1 不用 Hilt、不用 Room**：本阶段只有 4 个对象、零多绑定场景；Hilt 会带进 KSP，
  而 KSP 版本必须与 Kotlin 精确配对，是 CI 首跑最易碎的一环。手写 `ServiceLocator` 足够。
  M-A2 引入 Room 离线缓存时一并上 Hilt（那时确实需要）。

---

## 四、本机联调

```bash
# 1) 后端（dev profile，端口 9091，绑 0.0.0.0，真机可直连）
cd backend/app && mvn spring-boot:run

# 2) 告诉 App 后端在哪。local.properties 不入库。
cat > app-android/local.properties <<'EOF'
sdk.dir=/Users/<你>/Library/Android/sdk
huicui.devHost=192.168.1.23      # 真机填局域网 IP；模拟器可省略（默认 10.0.2.2）
EOF

# 3) 装
cd app-android && ./gradlew :app:installDebug
```

`debug` 变体的 `network_security_config` 放行明文 HTTP；`release` **禁止**（`src/main/res/xml` 那份）。
已实测：debug APK 内 `cleartextTrafficPermitted=true`。

### 联调账号（dev 种子）

| 场景 | 账号 | 期望 |
|---|---|---|
| 单账号口令登录 | `jx_co1` / `Admin@123` | 直接返回 token（催收员甲·捷信催收，8 项权限） |
| 一号多账号 | 手机号 `13900009000`，验证码 `000000` | 返回 `loginTicket` + 两个账号（PC·翠湖物业 / CO·捷信催收） |
| 限流 | 60s 内重复请求验证码 | `429`，`retryAfterSeconds` 驱动倒计时 |
| 口令错 | `jx_co1` / 任意错口令 | `401 AUTH_401` |

> dev 固定码 `000000` **只在 dev profile 存在**。非 dev 且短信通道未启用时，
> `/auth/sms-code` 一律 `502 BIZ_SMS_FAILED` —— 不存在「任何环境都能用的万能码」。

---

## 五、验证边界（诚实标注）

**CI / 本机可机器证明：**
- `:api-client:assemble` —— 契约能生成 Kotlin 客户端并编译（**实测通过**）。
- `:app:testDebugUnitTest` —— **35 个纯 JVM 用例，0 失败**：
  登录三态 + 401/403/429/502 + 畸形响应（14）；公海脱敏 + 拨号/抢单权限门控 + 金额格式（13）；
  离线读 + 403 不读缓存 + 搜索不污染缓存 + 登出清缓存（8）。
- `:app:lintDebug`、`:app:assembleDebug`、`:app:assembleRelease`（R8）—— 均通过。
- 真后端报文核对：登录四态、`/workbench`、`/cases`、`/sea`、`/notifications` 逐一用 curl 打过，
  并用**生成的客户端真去反序列化**了 `NotificationPage` 与 `WorkbenchData`。

**必须真机、CI 与我都做不了：**
- 在真手机上走通三种登录（口令 / 短信 / 一号多账号选账号）。
- 待办点进案件、公海抢单、断网后仍能读缓存案件 —— 逻辑有单测，**真机手感没有**。
- 首登强制改密的完整体感（`must_change_password` 的种子账号需另行准备）。
- **录音管线的物理前提**：你的 ROM 到底会不会录音、录到哪个目录、文件名长什么样。
  这三件事我在任何模拟器上都验证不了，只能靠首登引导第 ④ 步的测试通话。

---

## 六、录音管线（M-A2）

### 6.1 它做什么、不做什么

平台**不主动外呼、不感知拨打时机**（BR-M4-01b）。通话发生在你的手机上，录音由**系统自己录**。
App 只做四件事：拨号前记下「这通电话属于哪个案件」、通话结束后去录音目录找文件、
把文件匹配回案件、传给服务端。ASR 转写、计费、质检全在服务端。

**App 自己不录音。** AOSP 禁止第三方应用录到对方声音，所以走「读系统录音目录」（TD-1 机制A）。

### 6.2 三层检测（PRD §3.2）

| 层 | 机制 | 为什么需要它 |
|---|---|---|
| 主 | `FileObserver` 听 `CLOSE_WRITE` + `MOVED_TO` | 很多 ROM 先写临时文件再重命名，只听 `CLOSE_WRITE` 会漏 |
| 并行 | 窗口期内周期扫描目录 | `FileObserver` 在国产 ROM 上会被静默杀死、事件也会丢 |
| 兜底 | App 每次进前台增量扫描 + WorkManager 周期任务 | 用户打完电话把 App 划掉、一小时后再打开，录音必须还能捡回来 |

前台服务**只在「通话结束 → 匹配 → 入队」这 120 秒窗口期活着**，完成即自杀。
不做 7×24 常驻监听：那既费电，又是国产 ROM 的头号杀进程目标。

### 6.3 匹配规则：宁可问，不可错挂

录音一旦挂错案件，会污染另一个案件的转写、质检与存证 —— 而那些是要拿去法务举证的。
所以 `RecordingMatcher` 在拿不准时**不猜**，把选择权交回给催收员（「上传队列」页顶部）：

| 情形 | 判定 |
|---|---|
| 号码一致 + 落在通话时间窗内 | 直接匹配 |
| 号码与所有去电都不一致 | 判为无关文件（用户自己录的音，不碰） |
| 号码一致但时间窗外 | **交给用户确认**（号码是强证据，但不敢直接挂） |
| 文件名无号码，时间窗内唯一 | 直接匹配 |
| 时间窗内多通，且时间差 ≥15s | 取时间就近者 |
| 时间窗内多通，时间难分伯仲 | **交给用户二选一** |

### 6.4 接通判定（BR-APP-05：未接通不上传）

**不能用 `TelephonyCallback` 的 `OFFHOOK` 判接通** —— 外呼一拨出就 OFFHOOK，对方没接也会触发。
主信号是 `CallLog.Calls.duration`；**有录音文件本身也是接通的强证据**（ROM 只在接通后才录），
所以即便用户拒了 `READ_CALL_LOG`，有录音就传。

未接通（时长 <2s 且无录音）**不上传**：不浪费 ASR 分钟，也不往话术飞轮里灌忙音。

### 6.5 上传

- 队列在 Room 里，进程被杀/重启可恢复；WorkManager 负责网络约束与续跑。
- **`Idempotency-Key` = 文件 SHA-256**。同一文件重传，服务端返 `409 幂等键重放`，
  客户端**判为成功**（PRD §3.4）—— 绝不重复扣 ASR 分钟。
- 退避 1/2/4/8… 分钟，封顶 30 分钟，8 次后转「失败待手动」，队列页可一键重试。
- 半截文件不入队：大小连续 3 次采样不变才算写完（ROM 先写临时文件再重命名）。

### 6.6 本地文件策略（与 PRD 的一处有意偏离）

- **绝不删除、绝不修改系统录音目录里的文件。** 那是用户手机里的通话记录，不是我们的资产。
- 入队时把录音**复制进 App 私有目录**，上传的是副本。系统目录可能被 ROM 清理，
  而离线时一条录音可能要在队列里躺几小时。
- **上传成功即删副本**，不留 7 天（PRD §3.4 写的是保留 7 天）。理由：服务端已存了音频
  （V921 `audio_bytes`）且 App 可流式回听，本地再留一份只是徒增泄露面。
- 副本**未做静态加密**。私有目录在未 root 设备上其他 App 读不到，且副本通常只活几秒到几分钟。
  上 `EncryptedFile` 会让上传时必须先解密到临时明文文件，泄露面并没有真正减少。
  若将来要求静态加密，正确做法是流式加解密，届时再改。

### 6.7 权限与分发

`MANAGE_EXTERNAL_STORAGE`（Android 11+）是必需的：**SAF 授权的目录挂不了 `FileObserver`**，
拿不到「一挂断就有录音」的实时信号。PRD §4.2 已决定不上 Google Play、走 B2B 企业侧载。
手动选目录时用系统目录选择器（体验好），但把 tree URI 还原成绝对路径来用（`SafPaths`）。

### 6.8 退出登录会清空未上传的录音

队列里的录音属于上一个账号的案件，换人登录后必然 403。退出时清队列 + 删私有副本，
**系统录音目录里的原件不受影响**。队列非空时会先弹确认框。

---

## 七、真机验收清单（需要你）

1. 首登引导四步走完，第 ④ 步「测试通话」必须真的产出一条录音并进入队列 ——
   **这是唯一能证明整条链路通了的检验**，前三步全绿也不代表你的 ROM 真的录到了音。
2. 打一通真实催收电话，挂断后 2 分钟内「录音」Tab 出现该条并变「已上传」。
3. 关掉网络再打一通，队列显示「等待重试」；连上网后自动传完。
4. 连续拨打两个不同案件，确认两条录音各自挂对案件（不该出现「待确认」）。
5. 故意在通话中挂断（<2s），确认**不产生**上传项。
6. 把 App 从后台划掉，打一通电话，再打开 App —— 兜底扫描应把录音捡回来。
7. 记录你的机型 / ROM 版本 / 实际录音目录 / 文件名样例，回填 `RecordingDirectories` 的候选表。
