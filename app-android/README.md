# 有证慧催 · Android App（M-A1 骨架）

催收员端。**本 PR（PR-A1）只做：工程骨架 + 契约客户端 + 登录三态。**
拨号跳转、读系统录音目录、录音自动上传属于 **M-A2**，本仓库此刻**没有**这些代码，别到处找。

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

## 二、工程结构

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

## 三、本机联调

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

## 四、验证边界（诚实标注）

**CI / 本机可机器证明：**
- `:api-client:assemble` —— 契约能生成 Kotlin 客户端并编译（**实测通过**）。
- `:app:testDebugUnitTest` —— 登录三态 + 401/403/502/429 + 畸形响应，14 个纯 JVM 用例（**实测 14 passed / 0 failed**）。
- `:app:lintDebug`、`:app:assembleDebug` —— 产出可装的 debug APK（**实测通过**）。
- 真后端报文核对：四种响应形状与 `LoginResponseMapper` 的期望逐一对上（**已用 curl 实测**）。

**必须真机、CI 与我都做不了：**
- 在真手机上走通三种登录（口令 / 短信 / 一号多账号选账号）。
- 首登强制改密的完整体感（`must_change_password` 的种子账号需另行准备）。
- 任何与拨号、录音、上传相关的行为 —— **本 PR 里根本没有这些代码**。
