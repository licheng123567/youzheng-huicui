# UAT 自动联调与组织成员字段修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正组织/成员页面的账号和完整手机号展示，并在 `47.108.81.205` 建立由内网 Git `main` 自动触发、数据隔离且可重复验收的 UAT 环境。

**Architecture:** 先以 OpenAPI 为真值源扩展 `Org`，后端用 `org LEFT JOIN account` 一次返回负责人账号名和完整手机号，前端保持现有布局只替换字段并增加一列。UAT 使用独立 Compose project、镜像 tag 和 PostgreSQL volume；裸仓库 hook 只入队 SHA，带 `flock` 的 worker 串行检出并调用部署脚本，部署脚本在切换前完成构建、切换后执行快速硬门，失败时恢复上一镜像且绝不删除数据库。

**Tech Stack:** Java 21、Spring Boot 3.3、JdbcTemplate、JUnit 5/Mockito、OpenAPI、Vue 3/TypeScript、Playwright、Docker Compose、nginx、POSIX shell、Git hooks、PostgreSQL/pgvector。

---

## 文件边界

- `docs/api/openapi-core.yaml`：`Org` 契约的唯一真值源。
- `backend/app/src/main/java/com/youzheng/huicui/web/dto/OrgSystemDtos.java`：Java `OrgDto` 字段定义。
- `backend/app/src/main/java/com/youzheng/huicui/web/OrgSystemM1Controller.java`：组织列表 SQL、映射和创建响应。
- `backend/app/src/test/java/com/youzheng/huicui/web/OrgSystemM1ControllerTest.java`：契约字段和负责人连接查询的单元回归。
- `frontend/src/api/schema.d.ts`：由 OpenAPI 生成，不手工修改。
- `frontend/src/views/OrgMgmtView.vue`、`frontend/src/views/MembersView.vue`：保持现有页面结构，只纠正字段语义和手机号列。
- `frontend/e2e/members.spec.ts`：六角色边界、组织负责人和平台成员完整手机号真屏回归。
- `frontend/e2e/uat-smoke.spec.ts`、`frontend/playwright.uat.config.ts`：每次部署在 Compose 网络内运行的最小真屏硬门。
- `deploy/uat/docker-compose.uat.yml`：独立 UAT 服务、网络、资源上限和持久卷。
- `deploy/uat/Dockerfile.web`、`deploy/uat/Dockerfile.smoke`、`deploy/uat/nginx.conf`：同 SHA 前端镜像、真屏硬门镜像及 `/v1` 反向代理。
- `deploy/uat/verify.sh`：每次部署的 HTTP/API 快速硬门。
- `deploy/uat/reset.sh`：必须显式确认且只删除 UAT volume 的重置入口。
- `deploy/uat/deploy.sh`：单 SHA 顺序构建、切换、验证和镜像回退。
- `deploy/uat/worker.sh`：消费最新 SHA，使用 `flock` 防并发部署。
- `deploy/uat/post-receive`：只监听 `refs/heads/main` 并异步唤醒 worker。
- `deploy/uat/install-hook.sh`：安装 worker/hook 和服务器目录，不存储密钥。
- `deploy/uat/tests/static-contract.sh`：对 Compose、重置保护、hook 和 worker 做无破坏静态/伪命令测试。
- `deploy/uat/.env.example`、`deploy/uat/README.md`：服务器配置模板和操作手册。

### Task 1: 用测试锁定组织负责人账号与完整手机号契约

**Files:**
- Create: `backend/app/src/test/java/com/youzheng/huicui/web/OrgSystemM1ControllerTest.java`
- Modify: `docs/api/openapi-core.yaml:3732-3743`
- Modify: `backend/app/src/main/java/com/youzheng/huicui/web/dto/OrgSystemDtos.java:6-34`
- Modify: `backend/app/src/main/java/com/youzheng/huicui/web/OrgSystemM1Controller.java:98-135,207-209,436-446`

- [ ] **Step 1: 写当前实现会失败的契约与 SQL 测试**

```java
package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.DataRange;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.OrgSystemDtos.OrgDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgSystemM1ControllerTest {
    @Mock JdbcTemplate jdbc;
    @Mock OrgSystemAuditService audit;

    private OrgSystemM1Controller controller;

    @BeforeEach
    void setUp() {
        controller = new OrgSystemM1Controller(jdbc, audit, new ObjectMapper());
        SubjectContext.set(new CurrentSubject(
                "1", "平台超管", "1", "PLATFORM", "有证平台", "SA",
                Set.of("org.manage", "member.manage"), DataRange.UNRESTRICTED));
    }

    @AfterEach
    void tearDown() {
        SubjectContext.clear();
    }

    @Test
    void orgContractExposesOwnerUsernameAndOwnerPhone() {
        List<String> names = java.util.Arrays.stream(OrgDto.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(names).contains("ownerAccountId", "ownerUsername", "ownerPhone");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void listOrgsLeftJoinsTheOwnerAccount() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        controller.listOrgs(null, null, 1, 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("FROM org o LEFT JOIN account owner ON owner.id = o.owner_account_id")
                .contains("owner.username AS owner_username")
                .contains("owner.phone AS owner_phone");
    }
}
```

- [ ] **Step 2: 运行测试，确认是预期红灯**

Run: `mvn -f backend/app/pom.xml -Dtest=OrgSystemM1ControllerTest test`

Expected: 两个测试失败；第一个报告缺少 `ownerUsername`/`ownerPhone`，第二个报告 SQL 不含 `LEFT JOIN account owner`。不得通过删断言让测试变绿。

- [ ] **Step 3: 扩展 OpenAPI 和 Java DTO**

将 `Org` schema 的属性改为：

```yaml
    Org:
      type: object
      description: '组织（负责人账号名与完整手机号由 ownerAccountId 左连接 account 返回；B-04方案A：POST /orgs 201 响应及 PATCH /orgs/{id}/owner?resetPassword=true 响应含 ownerSetupToken 一次性明文；列表/GET 始终 null）'
      properties:
        id: { type: string }
        type: { $ref: '#/components/schemas/OrgTypeEnum' }
        name: { type: string }
        ownerAccountId: { type: string, description: '负责人 account.id；兼容字段，不得作为账号名展示' }
        ownerUsername: { type: [string, 'null'], description: '负责人登录账号名 account.username' }
        ownerPhone: { type: [string, 'null'], description: '负责人完整手机号 account.phone；当前需求明确不脱敏' }
        status: { type: string }
        ownerSetupToken: { type: [string, 'null'], description: '一次性凭据交付 token 明文（仅 POST /orgs 201 及 PATCH owner?resetPassword=true 响应出现一次，带外转交 owner；审计不记明文）' }
```

将 `OrgDto` 改为：

```java
public record OrgDto(
        String id,
        String type,
        String name,
        String ownerAccountId,
        String ownerUsername,
        String ownerPhone,
        String status,
        String ownerSetupToken) {}
```

同时把类注释中的字段清单改为 `Org{id,type,name,ownerAccountId,ownerUsername,ownerPhone,status}`。

- [ ] **Step 4: 用别名安全的 LEFT JOIN 实现列表查询与映射**

在 `listOrgs` 中将过滤条件改成 `o.type`、`o.status`、`o.id`，count 从 `org o` 查询；列表 SQL 和 mapper 使用以下内容：

```java
Long total = jdbc.queryForObject("SELECT count(*) FROM org o" + where, Long.class, args.toArray());

List<OrgDto> items = jdbc.query(
        "SELECT o.id, o.type, o.name, o.owner_account_id,"
                + " owner.username AS owner_username, owner.phone AS owner_phone, o.status"
                + " FROM org o LEFT JOIN account owner ON owner.id = o.owner_account_id"
                + where + " ORDER BY o.id LIMIT ? OFFSET ?",
        ORG_MAPPER, pageArgs.toArray());
```

```java
private static final RowMapper<OrgDto> ORG_MAPPER = (rs, i) -> new OrgDto(
        String.valueOf(rs.getLong("id")),
        rs.getString("type"),
        rs.getString("name"),
        idOrNull(rs, "owner_account_id"),
        rs.getString("owner_username"),
        rs.getString("owner_phone"),
        rs.getString("status"),
        null);
```

创建组织的响应构造器改为：

```java
return new OrgDto(String.valueOf(orgId), type, body.name(),
        String.valueOf(ownerAccountId), body.ownerAccount(), body.ownerPhone(),
        "ACTIVE", setupToken);
```

- [ ] **Step 5: 运行后端回归并确认通过**

Run: `mvn -f backend/app/pom.xml -Dtest=OrgSystemM1ControllerTest test`

Expected: `Tests run: 2, Failures: 0, Errors: 0` 和 `BUILD SUCCESS`。

Run: `mvn -f backend/app/pom.xml test`

Expected: `BUILD SUCCESS`；现有组织创建、权限和认证测试无回归。

- [ ] **Step 6: 提交后端与契约变更**

```bash
git add docs/api/openapi-core.yaml backend/app/src/main/java/com/youzheng/huicui/web/dto/OrgSystemDtos.java backend/app/src/main/java/com/youzheng/huicui/web/OrgSystemM1Controller.java backend/app/src/test/java/com/youzheng/huicui/web/OrgSystemM1ControllerTest.java
git commit -m "fix(orgs): return owner account and full phone"
```

### Task 2: 保持页面布局并纠正组织/成员字段展示

**Files:**
- Modify: `frontend/src/api/schema.d.ts`
- Modify: `frontend/src/views/OrgMgmtView.vue:105-128`
- Modify: `frontend/src/views/MembersView.vue:334-361`
- Modify: `frontend/e2e/members.spec.ts`

- [ ] **Step 1: 先写真屏失败用例**

在 `frontend/e2e/members.spec.ts` 的 SA 测试组中增加：

```ts
test('SA 看到组织负责人账号和完整手机号，且不再把 account.id 当账号', async ({ page }) => {
  await loginRole(page, 'SA')
  await page.goto('/org-mgmt')
  const cuihu = page.locator('tbody tr').filter({ hasText: '翠湖物业' }).first()
  await expect(cuihu).toContainText('cuihu_pl')
  await expect(cuihu).toContainText('13900000001')
  await expect(cuihu.locator('[data-field="owner-username"]')).toHaveText('cuihu_pl')
  await expect(cuihu.locator('[data-field="owner-phone"]')).toHaveText('13900000001')
})

test('SA 成员列表展示平台账号和完整手机号且仍只含 SA/SE', async ({ page }) => {
  await loginRole(page, 'SA')
  await page.goto('/members')
  const memberTable = page.locator('table').first()
  const admin = memberTable.locator('tbody tr').filter({ hasText: '平台超管' }).first()
  const operator = memberTable.locator('tbody tr').filter({ hasText: '平台运营' }).first()
  await expect(admin).toContainText('admin')
  await expect(admin).toContainText('13800000000')
  await expect(operator).toContainText('plat_se')
  await expect(operator).toContainText('13800000001')
  await expect(memberTable.locator('tbody tr').filter({ hasText: /cuihu_pl|jx_vl|jx_co1/ })).toHaveCount(0)
})
```

- [ ] **Step 2: 运行定向 E2E，确认组织字段用例为红灯**

先按 `.github/workflows/e2e.yml` 启动 pgvector、dev 后端和 Vite，然后运行：

Run: `cd frontend && npx playwright test e2e/members.spec.ts --grep "负责人账号和完整手机号|成员列表展示平台账号"`

Expected: 组织用例失败，实际单元格是数字 `ownerAccountId` 且缺少 `data-field="owner-phone"`；成员用例应通过，证明 `/members` 的账号和完整手机号数据链已经正确，问题不应靠改 seed 掩盖。

- [ ] **Step 3: 生成契约类型并检查字段名**

Run: `cd frontend && npm run gen:api`

Expected: `frontend/src/api/schema.d.ts` 中 `Org` 同时含 `ownerAccountId?: string`、`ownerUsername?: string | null`、`ownerPhone?: string | null`。

Run: `rg -n "ownerUsername|ownerPhone" frontend/src/api/schema.d.ts`

Expected: 两个字段均命中 `Org`，不得手改生成文件。

- [ ] **Step 4: 只改表格字段，不改变已确认布局和操作**

`OrgMgmtView.vue` 表头与行改为：

```vue
<th>负责人账号</th>
<th style="width:140px">负责人手机</th>
<th style="width:110px">状态</th>
<th style="width:130px">操作</th>
```

```vue
<td data-field="owner-username">{{ row.ownerUsername || '—' }}</td>
<td data-field="owner-phone">{{ row.ownerPhone || '—' }}</td>
<td><span class="tag" :class="row.status==='ACTIVE' ? 'suc' : 'inf'">{{ statusLabel(row.status) }}</span></td>
<td><button class="btn txt" @click="rebindOwner(row)">改绑负责人</button></td>
```

空表行 `colspan` 从 `5` 改为 `6`。

`MembersView.vue` 内嵌组织表同步增加负责人手机列，并使用相同字段：

```vue
<th>负责人账号</th>
<th style="width:140px">负责人手机</th>
<th style="width:180px">操作</th>
```

```vue
<td data-field="owner-username">{{ row.ownerUsername || '—' }}</td>
<td data-field="owner-phone">{{ row.ownerPhone || '—' }}</td>
```

空表行 `colspan` 从 `5` 改为 `6`。成员主表继续使用既有 `row.username` 和 `row.phone`，不得改成组织负责人字段，也不得新增手机号掩码。

- [ ] **Step 5: 运行生成漂移、构建和 E2E 回归**

Run: `cd frontend && npm run build`

Expected: `vue-tsc --noEmit` 和 `vite build` 均成功。

Run: `cd frontend && npx playwright test e2e/members.spec.ts`

Expected: `members.spec.ts` 全部通过；SA 只见 SA/SE，PL/VL 的成员管理权限行为不变，组织负责人显示账号名和 11 位完整手机号。

- [ ] **Step 6: 提交前端和生成类型**

```bash
git add frontend/src/api/schema.d.ts frontend/src/views/OrgMgmtView.vue frontend/src/views/MembersView.vue frontend/e2e/members.spec.ts
git commit -m "fix(ui): show correct organization owner contacts"
```

### Task 3: 用失败优先的静态测试建立独立 UAT Compose

**Files:**
- Create: `deploy/uat/tests/static-contract.sh`
- Create: `deploy/uat/docker-compose.uat.yml`
- Create: `deploy/uat/Dockerfile.web`
- Create: `deploy/uat/Dockerfile.smoke`
- Create: `deploy/uat/nginx.conf`
- Create: `deploy/uat/.env.example`
- Create: `frontend/e2e/uat-smoke.spec.ts`
- Create: `frontend/playwright.uat.config.ts`

- [ ] **Step 1: 创建静态契约测试**

```sh
#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
UAT="$ROOT/deploy/uat"

require() { grep -Eq -- "$2" "$1" || { echo "missing pattern '$2' in $1" >&2; exit 1; }; }
reject() { if grep -Eq -- "$2" "$1"; then echo "forbidden pattern '$2' in $1" >&2; exit 1; fi; }

test -f "$UAT/docker-compose.uat.yml"
test -f "$UAT/Dockerfile.web"
test -f "$UAT/Dockerfile.smoke"
test -f "$UAT/nginx.conf"
test -f "$ROOT/frontend/e2e/uat-smoke.spec.ts"
test -f "$ROOT/frontend/playwright.uat.config.ts"
require "$UAT/docker-compose.uat.yml" 'name: huicui-uat'
require "$UAT/docker-compose.uat.yml" '127\.0\.0\.1:9092:9091'
require "$UAT/docker-compose.uat.yml" '6090:80'
require "$UAT/docker-compose.uat.yml" 'huicui-uat-pgdata:'
require "$UAT/docker-compose.uat.yml" 'SPRING_PROFILES_ACTIVE: dev'
require "$UAT/docker-compose.uat.yml" 'memory: 256M'
require "$UAT/docker-compose.uat.yml" 'memory: 600M'
require "$UAT/docker-compose.uat.yml" 'memory: 128M'
reject "$UAT/docker-compose.uat.yml" 'huicui-pgdata:'
reject "$UAT/docker-compose.uat.yml" '5432:5432'
require "$UAT/nginx.conf" 'proxy_pass http://backend:9091;'
require "$UAT/docker-compose.uat.yml" 'name: huicui-uat-network'
require "$UAT/Dockerfile.smoke" 'mcr.microsoft.com/playwright:v1.61.1-noble'

docker compose --project-name huicui-uat \
  --env-file "$UAT/.env.example" -f "$UAT/docker-compose.uat.yml" config >/dev/null
echo 'uat static contract: PASS'
```

Run: `chmod +x deploy/uat/tests/static-contract.sh && deploy/uat/tests/static-contract.sh`

Expected: FAIL at `docker-compose.uat.yml` missing。

- [ ] **Step 2: 创建同 SHA 前端镜像**

```dockerfile
FROM node:20-bookworm-slim AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src ./src
RUN npm run build

FROM nginx:1.27-alpine
ARG HUICUI_REVISION=unknown
LABEL org.opencontainers.image.revision="${HUICUI_REVISION}"
COPY deploy/uat/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
```

- [ ] **Step 3: 创建 UAT nginx 配置**

```nginx
server {
  listen 80;
  server_name _;
  root /usr/share/nginx/html;
  index index.html;

  location /v1/ {
    proxy_pass http://backend:9091;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 220m;
    proxy_read_timeout 300s;
  }

  location / {
    try_files $uri $uri/ /index.html;
  }
}
```

- [ ] **Step 4: 创建独立的真屏 smoke 配置与镜像**

`frontend/e2e/uat-smoke.spec.ts`：

```ts
import { test, expect } from '@playwright/test'
import { loginRole } from './helpers'

test('UAT 组织负责人账号与完整手机号', async ({ page }) => {
  await loginRole(page, 'SA')
  await page.goto('/org-mgmt')
  const row = page.locator('tbody tr').filter({ hasText: '翠湖物业' }).first()
  await expect(row.locator('[data-field="owner-username"]')).toHaveText('cuihu_pl')
  await expect(row.locator('[data-field="owner-phone"]')).toHaveText('13900000001')
})

test('UAT 平台成员仍只有 SA/SE 且手机号完整', async ({ page }) => {
  await loginRole(page, 'SA')
  await page.goto('/members')
  const table = page.locator('table').first()
  await expect(table.locator('tbody tr').filter({ hasText: 'admin' }).first()).toContainText('13800000000')
  await expect(table.locator('tbody tr').filter({ hasText: 'plat_se' }).first()).toContainText('13800000001')
  await expect(table.locator('tbody tr').filter({ hasText: /cuihu_pl|jx_vl|jx_co1/ })).toHaveCount(0)
})
```

`frontend/playwright.uat.config.ts`：

```ts
import { defineConfig, devices } from '@playwright/test'

const artifacts = process.env.PLAYWRIGHT_ARTIFACT_DIR || 'uat-test-results'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'uat-smoke.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 30_000,
  reporter: [['list'], ['html', { outputFolder: `${artifacts}/html`, open: 'never' }]],
  outputDir: `${artifacts}/results`,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://web',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
```

`deploy/uat/Dockerfile.smoke`：

```dockerfile
FROM mcr.microsoft.com/playwright:v1.61.1-noble
WORKDIR /work
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/playwright.uat.config.ts ./
COPY frontend/e2e/helpers.ts frontend/e2e/uat-smoke.spec.ts ./e2e/
ENTRYPOINT ["npx", "playwright", "test", "--config=playwright.uat.config.ts"]
```

- [ ] **Step 5: 创建资源受限且完全隔离的 Compose**

```yaml
name: huicui-uat

services:
  db:
    image: pgvector/pgvector:pg16
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${UAT_POSTGRES_DB}
      POSTGRES_USER: ${UAT_POSTGRES_USER}
      POSTGRES_PASSWORD: ${UAT_POSTGRES_PASSWORD}
    volumes:
      - huicui-uat-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 12
    deploy:
      resources:
        limits: { memory: 256M }
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "3" }
    networks: [uat]

  backend:
    image: huicui-uat-backend:${UAT_IMAGE_TAG}
    restart: unless-stopped
    depends_on:
      db: { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: dev
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/${UAT_POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${UAT_POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${UAT_POSTGRES_PASSWORD}
      HUICUI_JWT_SECRET: ${UAT_JWT_SECRET}
      HUICUI_CRYPTO_KEY: ${UAT_CRYPTO_KEY}
      HUICUI_PUBLIC_BASE: ${UAT_PUBLIC_BASE:-http://47.108.81.205:6090}
      JAVA_OPTS: -XX:MaxRAMPercentage=55 -XX:+ExitOnOutOfMemoryError
    ports:
      - "127.0.0.1:9092:9091"
    deploy:
      resources:
        limits: { memory: 600M }
    logging:
      driver: json-file
      options: { max-size: "50m", max-file: "5" }
    networks: [uat]

  web:
    image: huicui-uat-web:${UAT_IMAGE_TAG}
    restart: unless-stopped
    depends_on:
      backend: { condition: service_healthy }
    ports:
      - "6090:80"
    deploy:
      resources:
        limits: { memory: 128M }
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "3" }
    networks: [uat]

networks:
  uat:
    name: huicui-uat-network

volumes:
  huicui-uat-pgdata:
    name: huicui-uat-pgdata
```

- [ ] **Step 6: 创建无密钥配置模板**

```dotenv
UAT_IMAGE_TAG=bootstrap
UAT_POSTGRES_DB=huicui_uat
UAT_POSTGRES_USER=huicui_uat
UAT_POSTGRES_PASSWORD=replace-with-openssl-rand-hex-24
UAT_JWT_SECRET=replace-with-openssl-rand-hex-48
UAT_CRYPTO_KEY=replace-with-openssl-rand-hex-32
UAT_PUBLIC_BASE=http://47.108.81.205:6090
```

- [ ] **Step 7: 运行静态测试并提交**

Run: `deploy/uat/tests/static-contract.sh`

Expected: `uat static contract: PASS`，Compose 展开无变量或 schema 错误。

```bash
git add deploy/uat frontend/e2e/uat-smoke.spec.ts frontend/playwright.uat.config.ts
git commit -m "feat(uat): add isolated compose stack"
```

### Task 4: 实现快速硬门和带保护的显式重置

**Files:**
- Create: `deploy/uat/verify.sh`
- Create: `deploy/uat/reset.sh`
- Modify: `deploy/uat/tests/static-contract.sh`

- [ ] **Step 1: 先给静态测试增加验证与重置保护断言**

```sh
test -x "$UAT/verify.sh"
test -x "$UAT/reset.sh"
require "$UAT/verify.sh" 'admin plat_se cuihu_pl cuihu_pc jx_vl jx_co1'
require "$UAT/verify.sh" '13900000001'
require "$UAT/verify.sh" '13800000000'
require "$UAT/reset.sh" '--confirm'
require "$UAT/reset.sh" 'huicui-uat-pgdata'
reject "$UAT/reset.sh" 'huicui-pgdata'
```

Run: `deploy/uat/tests/static-contract.sh`

Expected: FAIL at `verify.sh` missing。

- [ ] **Step 2: 创建 HTTP/API 快速硬门**

```sh
#!/bin/sh
set -eu

BASE=${UAT_BASE_URL:-http://127.0.0.1:6090}
PASSWORD=${UAT_DEV_PASSWORD:-Admin@123}
ARTIFACT_DIR=${UAT_ARTIFACT_DIR:-/var/log/huicui-uat}
mkdir -p "$ARTIFACT_DIR"

request() {
  curl --fail-with-body --silent --show-error --max-time 20 "$@"
}

status=$(curl --silent --output /dev/null --write-out '%{http_code}' "$BASE/")
test "$status" = 200
request "$BASE/v1/actuator/health" | grep -q '"status":"UP"'
unauth=$(curl --silent --output /dev/null --write-out '%{http_code}' "$BASE/v1/me")
test "$unauth" = 401

token_for() {
  user=$1
  request -H 'Content-Type: application/json' -X POST "$BASE/v1/auth/login" \
    --data "{\"mode\":\"password\",\"username\":\"$user\",\"password\":\"$PASSWORD\"}" |
    python3 -c 'import json,sys; value=json.load(sys.stdin).get("token"); assert value; print(value)'
}

for user in admin plat_se cuihu_pl cuihu_pc jx_vl jx_co1; do
  token_for "$user" >/dev/null
done

sa_token=$(token_for admin)
request -H "Authorization: Bearer $sa_token" "$BASE/v1/orgs?page=1&size=50" >"$ARTIFACT_DIR/orgs.json"
request -H "Authorization: Bearer $sa_token" "$BASE/v1/members?page=1&size=50" >"$ARTIFACT_DIR/members.json"

python3 - "$ARTIFACT_DIR/orgs.json" "$ARTIFACT_DIR/members.json" <<'PY'
import json, sys
orgs = json.load(open(sys.argv[1], encoding='utf-8'))['items']
members = json.load(open(sys.argv[2], encoding='utf-8'))['items']
cuihu = next(x for x in orgs if x['name'] == '翠湖物业')
assert cuihu['ownerUsername'] == 'cuihu_pl', cuihu
assert cuihu['ownerPhone'] == '13900000001', cuihu
actual = {(x['username'], x['phone'], x['role']) for x in members}
assert ('admin', '13800000000', 'SA') in actual, actual
assert ('plat_se', '13800000001', 'SE') in actual, actual
assert all(role in {'SA', 'SE'} for _, _, role in actual), actual
PY

echo "uat verify: PASS ($BASE)"
```

- [ ] **Step 3: 创建 fail-closed 重置脚本**

```sh
#!/bin/sh
set -eu

if [ "${1:-}" != "--confirm" ] || [ "${2:-}" != "huicui-uat" ] || [ "$#" -ne 2 ]; then
  echo 'usage: reset.sh --confirm huicui-uat' >&2
  exit 64
fi

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}
STATE=${UAT_STATE_DIR:-/var/lib/huicui-uat}
COMPOSE="$ROOT/deploy/uat/docker-compose.uat.yml"
test -f "$ENV_FILE"
test -f "$COMPOSE"
test -s "$STATE/active-sha"
tag=$(cat "$STATE/active-sha")

docker compose --project-name huicui-uat --env-file "$ENV_FILE" -f "$COMPOSE" down
docker volume rm huicui-uat-pgdata
UAT_IMAGE_TAG="$tag" docker compose --project-name huicui-uat --env-file "$ENV_FILE" -f "$COMPOSE" up -d
UAT_BASE_URL=${UAT_BASE_URL:-http://127.0.0.1:6090} "$ROOT/deploy/uat/verify.sh"
```

这里使用精确字面量而不是 glob/变量拼接删除 volume；脚本不得接受其他确认值。

- [ ] **Step 4: 运行安全测试和 shell 语法检查**

Run: `chmod +x deploy/uat/verify.sh deploy/uat/reset.sh && sh -n deploy/uat/verify.sh deploy/uat/reset.sh`

Expected: 无输出，退出码 0。

Run: `sh -c 'deploy/uat/reset.sh >/dev/null 2>&1; test "$?" -eq 64'`

Expected: 只打印用法，不调用 Docker，不删除任何 volume。

Run: `deploy/uat/tests/static-contract.sh`

Expected: `uat static contract: PASS`。

- [ ] **Step 5: 提交验证与重置脚本**

```bash
git add deploy/uat/verify.sh deploy/uat/reset.sh deploy/uat/tests/static-contract.sh
git commit -m "feat(uat): add smoke gate and guarded reset"
```

### Task 5: 实现异步同 SHA 部署、串行队列和失败回退

**Files:**
- Create: `deploy/uat/deploy.sh`
- Create: `deploy/uat/worker.sh`
- Create: `deploy/uat/post-receive`
- Create: `deploy/uat/install-hook.sh`
- Modify: `deploy/uat/tests/static-contract.sh`

- [ ] **Step 1: 先增加部署安全契约断言**

```sh
for file in deploy.sh worker.sh post-receive install-hook.sh; do test -x "$UAT/$file"; done
require "$UAT/post-receive" 'refs/heads/main'
require "$UAT/worker.sh" 'flock'
require "$UAT/worker.sh" 'pending-sha'
require "$UAT/deploy.sh" 'active-sha'
require "$UAT/deploy.sh" 'failed-sha'
require "$UAT/deploy.sh" 'HUICUI_REVISION'
require "$UAT/deploy.sh" 'verify.sh'
reject "$UAT/deploy.sh" 'volume rm'
reject "$UAT/deploy.sh" 'down -v'
```

Run: `deploy/uat/tests/static-contract.sh`

Expected: FAIL at `deploy.sh` missing。

- [ ] **Step 2: 创建单 SHA 部署脚本**

```sh
#!/bin/sh
set -eu

SHA=${1:?usage: deploy.sh COMMIT_SHA}
case "$SHA" in *[!0-9a-f]*|'') echo 'invalid commit SHA' >&2; exit 64;; esac
test "${#SHA}" -eq 40

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
STATE=${UAT_STATE_DIR:-/var/lib/huicui-uat}
LOG_DIR=${UAT_LOG_DIR:-/var/log/huicui-uat}
ENV_FILE=${UAT_ENV_FILE:-/root/huicui-uat.env}
REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
COMPOSE="$ROOT/deploy/uat/docker-compose.uat.yml"
ACTIVE="$STATE/active-sha"
FAILED="$STATE/failed-sha"
mkdir -p "$STATE" "$LOG_DIR"
test -f "$ENV_FILE"

test "$(git --git-dir="$REPO" rev-parse "$SHA^{commit}")" = "$SHA"

previous=''
if [ -f "$ACTIVE" ]; then previous=$(cat "$ACTIVE"); fi

docker build --build-arg HUICUI_VERSION="sha-${SHA}" --build-arg HUICUI_REVISION="$SHA" \
  -f "$ROOT/deploy/Dockerfile" -t "huicui-uat-backend:$SHA" "$ROOT"
docker build --build-arg HUICUI_REVISION="$SHA" \
  -f "$ROOT/deploy/uat/Dockerfile.web" -t "huicui-uat-web:$SHA" "$ROOT"
docker build -f "$ROOT/deploy/uat/Dockerfile.smoke" \
  -t "huicui-uat-smoke:$SHA" "$ROOT"

UAT_IMAGE_TAG="$SHA" docker compose --project-name huicui-uat \
  --env-file "$ENV_FILE" -f "$COMPOSE" config >/dev/null

if UAT_IMAGE_TAG="$SHA" docker compose --project-name huicui-uat \
     --env-file "$ENV_FILE" -f "$COMPOSE" up -d && \
   UAT_ARTIFACT_DIR="$LOG_DIR/$SHA" "$ROOT/deploy/uat/verify.sh" && \
   docker run --rm --network huicui-uat-network --memory=512m \
     -e PLAYWRIGHT_BASE_URL=http://web \
     -e PLAYWRIGHT_ARTIFACT_DIR=/artifacts \
     -v "$LOG_DIR/$SHA:/artifacts" "huicui-uat-smoke:$SHA"; then
  printf '%s\n' "$SHA" >"$ACTIVE.tmp"
  mv "$ACTIVE.tmp" "$ACTIVE"
  rm -f "$FAILED"
  echo "deploy PASS $SHA"
  exit 0
fi

printf '%s\n' "$SHA" >"$FAILED"
echo "deploy FAIL $SHA" >&2
if [ -n "$previous" ]; then
  UAT_IMAGE_TAG="$previous" docker compose --project-name huicui-uat \
    --env-file "$ENV_FILE" -f "$COMPOSE" up -d backend web
fi
docker compose --project-name huicui-uat --env-file "$ENV_FILE" -f "$COMPOSE" ps >"$LOG_DIR/$SHA-compose-ps.txt" || true
docker compose --project-name huicui-uat --env-file "$ENV_FILE" -f "$COMPOSE" logs --tail=300 backend web >"$LOG_DIR/$SHA-containers.log" 2>&1 || true
exit 1
```

- [ ] **Step 3: 创建 latest-wins 串行 worker**

```sh
#!/bin/sh
set -eu

REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
SRC=${UAT_SRC:-/root/huicui-uat-src}
STATE=${UAT_STATE_DIR:-/var/lib/huicui-uat}
LOG_DIR=${UAT_LOG_DIR:-/var/log/huicui-uat}
QUEUE="$STATE/pending-sha"
mkdir -p "$SRC" "$STATE" "$LOG_DIR"

exec 9>"$STATE/deploy.lock"
flock 9

while [ -s "$QUEUE" ]; do
  sha=$(cat "$QUEUE")
  rm -f "$QUEUE"
  log="$LOG_DIR/$sha.log"
  if {
    echo "started $(date -u +%FT%TZ) $sha"
    git --git-dir="$REPO" --work-tree="$SRC" checkout -f "$sha"
    test "$(git --git-dir="$REPO" rev-parse "$sha^{commit}")" = "$sha"
    (cd "$SRC" && ./deploy/uat/deploy.sh "$sha")
    echo "finished $(date -u +%FT%TZ) $sha"
  } >>"$log" 2>&1; then
    :
  else
    printf '%s\n' "$sha" >"$STATE/failed-sha"
    echo "failed $(date -u +%FT%TZ) $sha" >>"$log"
  fi
done
```

- [ ] **Step 4: 创建只监听 main 的短 hook**

```sh
#!/bin/sh
set -eu

STATE=${UAT_STATE_DIR:-/var/lib/huicui-uat}
RUNNER=${UAT_RUNNER:-/opt/huicui-uat/bin/worker.sh}
mkdir -p "$STATE"

while read -r oldrev newrev refname; do
  if [ "$refname" = refs/heads/main ] && [ "$newrev" != 0000000000000000000000000000000000000000 ]; then
    tmp="$STATE/pending-sha.$$"
    printf '%s\n' "$newrev" >"$tmp"
    mv "$tmp" "$STATE/pending-sha"
    nohup "$RUNNER" >/dev/null 2>&1 &
  fi
done
```

- [ ] **Step 5: 创建安装脚本**

```sh
#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
BARE_REPO=${UAT_REPO:-/root/repos/youzheng-huicui.git}
INSTALL_DIR=${UAT_INSTALL_DIR:-/opt/huicui-uat/bin}
STATE=${UAT_STATE_DIR:-/var/lib/huicui-uat}
LOG_DIR=${UAT_LOG_DIR:-/var/log/huicui-uat}

test -d "$BARE_REPO/objects"
mkdir -p "$INSTALL_DIR" "$STATE" "$LOG_DIR" /root/huicui-uat-src
install -m 0755 "$ROOT/deploy/uat/worker.sh" "$INSTALL_DIR/worker.sh"
install -m 0755 "$ROOT/deploy/uat/post-receive" "$BARE_REPO/hooks/post-receive"
git --git-dir="$BARE_REPO" config receive.denyNonFastForwards true
echo "installed UAT hook: $BARE_REPO/hooks/post-receive"
```

- [ ] **Step 6: 验证脚本语法、安全断言并提交**

Run: `chmod +x deploy/uat/deploy.sh deploy/uat/worker.sh deploy/uat/post-receive deploy/uat/install-hook.sh`

Run: `sh -n deploy/uat/deploy.sh deploy/uat/worker.sh deploy/uat/post-receive deploy/uat/install-hook.sh`

Expected: 无输出，退出码 0。

Run: `deploy/uat/tests/static-contract.sh`

Expected: `uat static contract: PASS`；部署脚本不包含自动删卷命令，hook 只关注 main，worker 包含队列和 `flock`。

```bash
git add deploy/uat
git commit -m "feat(uat): deploy latest main SHA asynchronously"
```

### Task 6: 写清安装、访问、故障和完整测试入口

**Files:**
- Create: `deploy/uat/README.md`
- Modify: `deploy/uat/tests/static-contract.sh`
- Modify: `frontend/playwright.config.ts:1-27`

- [ ] **Step 1: 在静态测试中锁住运维文档的必要命令**

```sh
test -f "$UAT/README.md"
require "$UAT/README.md" 'http://47\.108\.81\.205:6090'
require "$UAT/README.md" 'reset\.sh --confirm huicui-uat'
require "$UAT/README.md" '/var/log/huicui-uat'
require "$UAT/README.md" 'npx playwright test'
require "$UAT/README.md" '不得导入真实'
```

Run: `deploy/uat/tests/static-contract.sh`

Expected: FAIL at `README.md` missing。

- [ ] **Step 2: 让现有全量 Playwright 可显式指向 UAT 且不启动本地 Vite**

将 `frontend/playwright.config.ts` 的顶层常量、`baseURL` 和 `webServer` 改为：

```ts
import { defineConfig, devices } from '@playwright/test'

const externalBaseURL = process.env.PLAYWRIGHT_BASE_URL

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: { timeout: 8_000 },
  use: {
    baseURL: externalBaseURL || 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: externalBaseURL ? undefined : {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
```

Run: `cd frontend && PLAYWRIGHT_BASE_URL=http://127.0.0.1:9 npx playwright test --list`

Expected: 列出测试但不出现 `npm run dev`/Vite 启动日志；命令不会访问不可达 URL，因为 `--list` 不执行用例。

- [ ] **Step 3: 创建操作手册，使用下面的完整章节与命令**

````markdown
# 有证慧催 UAT

临时入口：`http://47.108.81.205:6090`。只允许合成数据，不得导入真实业主、电话、录音或现有数据库 dump。

## 首次安装

```bash
cp deploy/uat/.env.example /root/huicui-uat.env
db_secret=$(openssl rand -hex 24)
jwt_secret=$(openssl rand -hex 48)
crypto_secret=$(openssl rand -hex 32)
sed -i "s|replace-with-openssl-rand-hex-24|$db_secret|" /root/huicui-uat.env
sed -i "s|replace-with-openssl-rand-hex-48|$jwt_secret|" /root/huicui-uat.env
sed -i "s|replace-with-openssl-rand-hex-32|$crypto_secret|" /root/huicui-uat.env
chmod 600 /root/huicui-uat.env
./deploy/uat/install-hook.sh
```

首次部署由安装者把 `main` 的 40 位 SHA 写入 `/var/lib/huicui-uat/pending-sha` 后执行 `/opt/huicui-uat/bin/worker.sh`。以后每次 push 内网 Git 的 `main` 都会异步部署最新 SHA。

## 状态与日志

```bash
cat /var/lib/huicui-uat/active-sha
cat /var/lib/huicui-uat/failed-sha
docker compose --project-name huicui-uat --env-file /root/huicui-uat.env -f /root/huicui-uat-src/deploy/uat/docker-compose.uat.yml ps
tail -n 200 /var/log/huicui-uat/*.log
```

构建失败不会切换镜像；启动或快速验收失败会恢复上一 `active-sha` 的 backend/web。数据库迁移不会自动回滚，任何失败都不会自动删除 UAT 数据卷。

## 快速验收与显式重置

```bash
cd /root/huicui-uat-src
./deploy/uat/verify.sh
./deploy/uat/reset.sh --confirm huicui-uat
```

普通部署保留验收数据；只有第二条命令会删除 `huicui-uat-pgdata` 并恢复固定 seed。

## 完整测试

```bash
mvn -f backend/app/pom.xml test
python3 backend/scripts/route_coverage.py
cd frontend
npm run gen:api
npm run build
PLAYWRIGHT_BASE_URL=http://47.108.81.205:6090 npx playwright test
```

每次自动部署的 UAT smoke 报告、截图和 trace 放入 `/var/log/huicui-uat/<sha>/`；全量测试报告由运行它的工作站或 CI 保存。第三方短信、易保全、ASR、LLM 和支付保持关闭。

## HTTPS 切换

当 `uat.cuiai.doorai.cn` 的 A 记录指向 `47.108.81.205` 后，在宝塔 nginx 终止 TLS 并反代 `http://127.0.0.1:6090`；随后把 `/root/huicui-uat.env` 的 `UAT_PUBLIC_BASE` 改为 `https://uat.cuiai.doorai.cn` 并重启 UAT backend/web。应用代码和 Compose 端口不变。
````

- [ ] **Step 4: 运行文档契约并提交**

Run: `deploy/uat/tests/static-contract.sh`

Expected: `uat static contract: PASS`。

```bash
git add deploy/uat/README.md deploy/uat/tests/static-contract.sh frontend/playwright.config.ts
git commit -m "docs(uat): document installation and recovery"
```

### Task 7: 本地全量验证并推送内网 Git

**Files:**
- Verify only; do not edit generated artifacts except `frontend/src/api/schema.d.ts` from Task 2.

- [ ] **Step 1: 验证工作树与提交序列**

Run: `git status --short`

Expected: 无输出。

Run: `git log --oneline -7`

Expected: 依次包含设计提交和本计划的功能、测试、运维文档提交。

- [ ] **Step 2: 运行契约和后端全量测试**

Run: `python3 backend/scripts/route_coverage.py`

Expected: 契约端点无缺失；字段增加不改变路由数量。

Run: `mvn -f backend/app/pom.xml test`

Expected: `BUILD SUCCESS`，失败数和错误数均为 0。

- [ ] **Step 3: 验证生成类型无漂移并构建前端**

Run: `cd frontend && npm run gen:api && git diff --exit-code -- src/api/schema.d.ts`

Expected: 无 diff。

Run: `cd frontend && npm run build`

Expected: `vite build` 成功。

- [ ] **Step 4: 运行 UAT 静态安全测试与成员真屏回归**

Run: `deploy/uat/tests/static-contract.sh`

Expected: `uat static contract: PASS`。

Run: `cd frontend && npx playwright test e2e/members.spec.ts`

Expected: 全部通过并覆盖负责人账号、完整手机号及 BR-M1-04a。

- [ ] **Step 5: 推送同一 SHA 到内网备份仓库**

Run: `git push backup main`

Expected: `main -> main`，无 non-fast-forward。

Run: `local_sha=$(git rev-parse main); remote_sha=$(ssh -o BatchMode=yes root@47.108.81.205 'git --git-dir=/root/repos/youzheng-huicui.git rev-parse refs/heads/main'); test "$local_sha" = "$remote_sha"; printf '%s\n' "$local_sha"`

Expected: 打印一个 40 位 SHA 并退出 0。

### Task 8: 在 `47.108.81.205` 安装并首次部署 UAT

**Files:**
- Server-only: `/root/huicui-uat.env`
- Server-only: `/opt/huicui-uat/bin/worker.sh`
- Server-only: `/root/repos/youzheng-huicui.git/hooks/post-receive`
- Server-only: `/var/lib/huicui-uat/*`
- Server-only: `/var/log/huicui-uat/*`

- [ ] **Step 1: 只读确认不会碰现有栈**

Run:

```bash
ssh -o BatchMode=yes root@47.108.81.205 'docker ps --format "{{.Names}} {{.Ports}}"; docker volume ls --format "{{.Name}}"; git --git-dir=/root/repos/youzheng-huicui.git rev-parse refs/heads/main'
```

Expected: 现有 `huicui-*` 容器与 `huicui-pgdata` 可见；目标 SHA 等于 Task 7 的本地 SHA。记录结果，后续不得停止、重命名或删除这些对象。

- [ ] **Step 2: 检出目标 SHA、生成独立密钥并安装 hook**

Run:

```bash
ssh -o BatchMode=yes root@47.108.81.205 'mkdir -p /root/huicui-uat-src /var/lib/huicui-uat /var/log/huicui-uat; sha=$(git --git-dir=/root/repos/youzheng-huicui.git rev-parse refs/heads/main); git --git-dir=/root/repos/youzheng-huicui.git --work-tree=/root/huicui-uat-src checkout -f "$sha"; cp /root/huicui-uat-src/deploy/uat/.env.example /root/huicui-uat.env; db_secret=$(openssl rand -hex 24); jwt_secret=$(openssl rand -hex 48); crypto_secret=$(openssl rand -hex 32); sed -i "s|replace-with-openssl-rand-hex-24|$db_secret|" /root/huicui-uat.env; sed -i "s|replace-with-openssl-rand-hex-48|$jwt_secret|" /root/huicui-uat.env; sed -i "s|replace-with-openssl-rand-hex-32|$crypto_secret|" /root/huicui-uat.env; chmod 600 /root/huicui-uat.env; cd /root/huicui-uat-src; ./deploy/uat/install-hook.sh; printf "%s\n" "$sha" >/var/lib/huicui-uat/pending-sha; /opt/huicui-uat/bin/worker.sh'
```

Expected: worker 返回；日志 `/var/log/huicui-uat/<sha>.log` 包含 `deploy PASS <sha>`，`active-sha` 等于目标 SHA。

- [ ] **Step 3: 验证资源隔离和 HTTP 快速硬门**

Run:

```bash
ssh -o BatchMode=yes root@47.108.81.205 'cd /root/huicui-uat-src; ./deploy/uat/verify.sh; docker compose --project-name huicui-uat --env-file /root/huicui-uat.env -f deploy/uat/docker-compose.uat.yml ps; docker volume inspect huicui-uat-pgdata --format "{{.Name}}"'
```

Expected: `uat verify: PASS`；db/backend/web 均 healthy；volume 只输出 `huicui-uat-pgdata`。

Run: `curl --fail-with-body --silent --show-error http://47.108.81.205:6090/ >/dev/null`

Expected: 退出 0，公网临时入口可访问。

- [ ] **Step 4: 浏览器人工确认用户反馈入口**

使用 SA 登录 `http://47.108.81.205:6090`，打开“系统管理 → 组织管理”和“系统管理 → 成员管理”：

- “翠湖物业”负责人账号为 `cuihu_pl`，负责人手机为完整 `13900000001`。
- 平台成员只出现 SA/SE；`admin` 手机为完整 `13800000000`，`plat_se` 手机为完整 `13800000001`。
- 页面结构、菜单、按钮位置与已确认版本一致。
- 将临时入口交付给用户，用于反馈具体页面和区域。

Expected: 四项均满足；截图保存到 `/var/log/huicui-uat/<sha>/manual-org-members.png`。

### Task 9: 验证自动触发与失败保留行为

**Files:**
- Verify only.

- [ ] **Step 1: 用无代码空提交验证 post-receive 异步链路**

```bash
git commit --allow-empty -m "chore(uat): verify automatic deployment hook"
git push backup main
```

Expected: push 很快返回，不等待服务器镜像构建。

- [ ] **Step 2: 轮询新 SHA 的部署状态**

Run:

```bash
sha=$(git rev-parse HEAD)
ssh -o BatchMode=yes root@47.108.81.205 "test \"\$(cat /var/lib/huicui-uat/active-sha)\" = '$sha' && grep -q 'deploy PASS $sha' /var/log/huicui-uat/$sha.log"
```

Expected: 构建完成后退出 0；`active-sha` 与刚 push 的 SHA 完全一致。

- [ ] **Step 3: 复验旧数据卷未被普通部署重建**

Run:

```bash
ssh -o BatchMode=yes root@47.108.81.205 'before=$(docker volume inspect huicui-uat-pgdata --format "{{.CreatedAt}}"); cd /root/huicui-uat-src; ./deploy/uat/verify.sh >/dev/null; after=$(docker volume inspect huicui-uat-pgdata --format "{{.CreatedAt}}"); test "$before" = "$after"; printf "%s\n" "$after"'
```

Expected: 创建时间前后一致，普通自动部署保留 UAT 数据。

- [ ] **Step 4: 最终交付状态**

Run: `git status --short --branch`

Expected: 工作树干净；`main` 与内网 `backup/main` 同 SHA。向用户交付：

- 临时反馈地址 `http://47.108.81.205:6090`。
- 当前活动 SHA。
- 组织/成员账号和完整手机号验证结果。
- DNS 未就绪时继续使用 6090；`uat.cuiai.doorai.cn` 解析后按 README 切 HTTPS。

## 完成定义

- 所有代码和脚本测试均通过，工作树干净。
- 内网 Git `main`、UAT `active-sha`、前后端镜像 revision 三者相同。
- `huicui-uat` 的容器、网络和 volume 与现有 `huicui` 完全分离。
- 自动部署失败不会删库，普通部署不会重置 UAT 数据。
- 组织管理显示账号名和完整手机号；成员管理继续只管平台 SA/SE 并显示完整手机号。
- 用户可通过 `http://47.108.81.205:6090` 指明具体页面和区域反馈问题。
