# 校园 CAS 登录报 CAMPUS_ACCOUNT_NOT_FOUND 排查报告

<!-- 版本信息 -->
> **文档版本**: 1.0.0
> **最后更新**: 2026-09-24
> **排查范围**: standalone 部署（gordanshop.com，Cloudflare Tunnel → 本机 ai-gateway-standalone）
> **结论**: 非密码问题；平台用户表 `sldd_system_users` 为空导致身份映射失败
<!-- /版本信息 -->

## 1. 问题现象

访问 `https://gordanshop.com/admin/login`，输入学校账号密码后返回：

```json
{
  "timestamp": "2026-09-24 14:04:54",
  "status": 401,
  "error": "Unauthorized",
  "message": "校园账号未关联平台用户",
  "errorCode": "CAMPUS_ACCOUNT_NOT_FOUND",
  "path": "/api/security"
}
```

网关日志对应记录（`logs/jairouter.log`）：

```text
2026-09-24 14:04:54.450 [boundedElastic-14] WARN
  o.u.m.c.exceptionhandler.SecurityExceptionHandler - 认证失败: CAMPUS_ACCOUNT_NOT_FOUND - 校园账号未关联平台用户
2026-09-24 14:05:26.666 [boundedElastic-7]  WARN
  o.u.m.c.exceptionhandler.SecurityExceptionHandler - 认证失败: CAMPUS_ACCOUNT_NOT_FOUND - 校园账号未关联平台用户
```

## 2. 结论速览

| 判断 | 结论 |
|---|---|
| 学校账号密码是否错误 | **否**，CAS 认证已成功通过 |
| CAS 服务是否故障 | **否**，可见多次 302 正常跳转到 `cas.ntit.edu.cn` |
| 真实失败环节 | 网关侧「CAS 身份 → 平台用户」映射 |
| 直接原因 | `sldd_system_users` 表为 **0 行**，任何校园账号都无法匹配 |
| 是否代码缺陷 | 否，是**设计行为**：本地用户不存在时拒绝登录，不自动创建平台用户 |
| 是否有连带阻塞 | 有，即使补齐用户，`CAMPUS_ADMIN_ACCOUNTS` 为空仍无法进入管理后台 |

## 3. 认证与身份映射链路

```text
浏览器 /admin/login
  └─ 页面仅有「学校统一身份认证」按钮（frontend/src/views/Login.vue:33-36）
      └─ GET /api/auth/cas/login?target=/admin/auth/callback
          └─ 302 → https://cas.ntit.edu.cn/cas/login?service=https://gordanshop.com/api/auth/cas/callback?state=xxx
              └─ 用户在学校 CAS 输入账号密码 ✅ 认证成功
                  └─ GET /api/auth/cas/callback?ticket=...&state=...
                      └─ CasProtocolClient.validate() 服务端校验 ticket ✅ 成功
                          └─ CampusIdentityService.resolve() ❌ 在此失败
```

失败点代码（`src/main/java/org/unreal/modelrouter/auth/campus/service/CampusIdentityService.java`）：

```java
// 第 36-38 行：取 CAS 身份中的账号
String account = trim(casIdentity.attribute("account"));
String localAccount = trim(casIdentity.attribute("localAccount"));
String lookupAccount = firstNonBlank(localAccount, account);   // 优先 localAccount

// 第 50-51 行：按账号查平台用户，查不到即拒绝
PlatformSystemUserEntity systemUser = systemUserRepository.findByUserId(lookupAccount)
        .orElseThrow(() -> authError("校园账号未关联平台用户", "CAMPUS_ACCOUNT_NOT_FOUND"));
```

对应查询（`PlatformSystemUserRepository.findByUserId`）等价于：

```sql
SELECT * FROM sldd_system_users WHERE user_id = :lookupAccount;
```

## 4. 排查证据

### 4.1 平台用户表为空（决定性证据）

```console
$ docker exec ai-gateway-postgres psql -U ai_gateway -d ai_gateway \
    -c "SELECT count(*) FROM sldd_system_users;"
 total
-------
     0
```

关联表行数：

| 表 | 行数 | 说明 |
|---|---:|---|
| `sldd_system_users` | **0** | 平台用户表（CAS 靠它匹配 `user_id`） |
| `sldd_system_user_company` | 0 | 平台用户-企业关系 |
| `ai_enterprise` | 0 | 企业信息 |
| `ai_api_key` | 0 | 平台 API Key |
| `ai_user_free_quota` | 0 | 免费额度 |
| `campus_identity_binding` | 0 | CAS 身份绑定（首次成功登录后才写入） |

`sldd_system_users` 是**算力平台用户表的只读镜像**（实体注释明确标注“映射 sldd_system_users 表（只读）”），正常应由平台侧同步写入。standalone 部署下该表未被任何迁移或种子脚本填充，因此必然为空。

### 4.2 CAS 跳转与回调正常

日志中可见正常的登录跳转（节选）：

```text
2026-09-24 14:03:44.470 STATUS=302
  Location: https://cas.ntit.edu.cn/cas/login?service=https://gordanshop.com/api/auth/cas/callback?state%3DZkZt3bMgHgaFox6SZJSqPOlt61wy9hPlIb5c3faMwP0
```

环境变量确认 CAS 已启用且指向学校地址：

```text
CAMPUS_AUTH_ENABLED=true
CAMPUS_CAS_BASE_URL=https://cas.ntit.edu.cn/cas/
CAMPUS_PUBLIC_BASE_URL=https://gordanshop.com
```

### 4.3 干扰项排除

响应体中的 `"path": "/api/security"` **不代表真实请求路径**，不要据此排查。该值是硬编码兜底字符串：

```java
// SecurityExceptionHandler.java:131-135
private String getCurrentPath() {
    // 这里先返回一个默认值，后续可以通过RequestContextHolder或其他方式获取
    return "/api/security";
}
```

同理，`"status": 401` 来自异常自身的 HTTP 状态（`AuthenticationException` 默认 401），与安全模块无关。

## 5. 根因

**直接根因**：`sldd_system_users` 表为空，`findByUserId(lookupAccount)` 返回空 → 抛 `CAMPUS_ACCOUNT_NOT_FOUND`。

**根本原因**：该表是算力平台侧的只读镜像，本套 standalone 部署（Cloudflare Tunnel 暴露的 `gordanshop.com`）没有连接到算力平台的真实数据库，也没有任何同步任务或种子数据填充它。因此在这套环境里，CAS 登录**对任何账号都必然失败**——与具体是谁、密码是否正确无关。

**设计约束**：项目明确规定“本地用户不存在、未实名或状态无效时拒绝登录，**不自动创建平台用户**”（见 `docs/zh/deployment/production-readiness.md` 第 103 行），所以不存在“首次登录自动注册”的兜底路径。

## 6. 连带阻塞点（修复时必须一并处理）

补齐平台用户后，登录会**成功**，但仍进不了管理后台：

- `CAMPUS_ADMIN_ACCOUNTS` 当前为空（`.env:31`）→ CAS 登录只授予 `USER` 角色（`CampusIdentityService.java:43-48`）
- 管理后台接口要求 `ADMIN`：

```java
// SecurityConfiguration.java:146-148
authorizeExchangeSpec
    .pathMatchers("/api/admin/**", "/api/security/**", "/api/config/**", "/internal/**")
        .hasRole("ADMIN")
```

前端路由同样按 `ADMIN` 守卫（`frontend/src/router/index.ts` 多处 `roles: ['ADMIN']`）。

### 实名校验口径

`sldd_system_users` 的 `user_type` 与 `verify_status` 必须**严格成对**，否则会改报 `CAMPUS_REAL_NAME_REQUIRED`：

```java
// RealNameAuthUtils.isRealNameAuthenticated
return (userType == 1 && verifyStatus == 4)   // 企业用户 + 企业已实名
        || (userType == 2 && verifyStatus == 2);  // 个人用户 + 个人已实名
```

## 7. 修复方案

### 方案 A：补齐平台用户 + 加入管理员白名单（推荐）

适用于“本机演示 / 临时验证”场景。

前置：确认你的 CAS 账号（`localAccount`，通常是工号或学号）。

**第 1 步**：写入平台用户记录。

```sql
INSERT INTO sldd_system_users (id, user_id, username, company_id, user_type, verify_status)
VALUES (1001, '<你的CAS账号>', '<你的姓名或账号>', NULL, 2, 2);
```

- `user_id` 必须与 CAS 返回的 `localAccount` 完全一致（若 CAS 不返回 `localAccount`，则用 `account`）
- `user_type=2` + `verify_status=2` 表示个人已实名
- `company_id` 可为空，为空时按个人用户处理

**第 2 步**：把账号加入管理员白名单，编辑 `.env`：

```text
CAMPUS_ADMIN_ACCOUNTS=<你的CAS账号>
```

**第 3 步**：重启网关容器使环境变量生效，然后重新登录验证。

> 注意：`CAMPUS_ADMIN_ACCOUNTS` 支持同时匹配 CAS 的 `account` 与 `localAccount`，且大小写不敏感。

### 方案 B：临时启用应急管理员登录

适用于“平台数据尚未就绪，但要先进后台看功能”的场景。

**第 1 步**：编辑 `.env`：

```text
EMERGENCY_LOGIN_ENABLED=true
EMERGENCY_LOGIN_ALLOWED_CIDRS=127.0.0.1/32,::1/128
```

**第 2 步**：重启容器，访问 `/login/emergency`，使用 `INITIAL_ADMIN_USERNAME` / `INITIAL_ADMIN_PASSWORD`（见 `.env`）登录。

**限制**：白名单当前仅允许本机回环地址，从公网经 `gordanshop.com` 访问会被拒绝并返回 `EMERGENCY_LOGIN_IP_DENIED`。走此方案需同时在本机浏览器通过 `http://localhost:38008` 访问，或按需放宽 `EMERGENCY_LOGIN_ALLOWED_CIDRS`（不推荐对公网开放）。

### 方案 C：由算力平台侧同步真实用户数据（生产正解）

适用于正式上线。

- 不要再手工造数据；应让算力平台把真实用户表（含 `user_id`、`user_type`、`verify_status`）同步到网关所用的 PostgreSQL
- 同步到位后，用户只需在校方系统完成实名，即可用 CAS 正常登录，无需任何网关侧改动
- 建议补齐后核对：`SELECT count(*) FROM sldd_system_users WHERE verify_status IN (2,4);` 应等于预期实名用户数

## 8. 验证清单

修复后按顺序确认：

1. `SELECT count(*) FROM sldd_system_users WHERE user_id = '<账号>';` 返回 1
2. `SELECT user_type, verify_status FROM sldd_system_users WHERE user_id = '<账号>';` 为 `(2,2)` 或 `(1,4)`
3. 重新走 `/admin/login` → CAS → 回调，不再出现 `CAMPUS_ACCOUNT_NOT_FOUND`
4. 若出现 `CAMPUS_REAL_NAME_REQUIRED`，说明第 2 步的实名组合不合法
5. 登录后 `GET /api/auth/session` 返回的 `roles` 含 `ADMIN`、`portals` 含 `ADMIN`
6. 能正常打开 `/dashboard/main` 及管理后台各页面
7. `SELECT * FROM campus_identity_binding;` 出现一条绑定记录（首次成功登录后写入）

## 9. 建议

1. **部署文档补充前置检查**：在 standalone / 隧道演示部署流程中，增加“确认 `sldd_system_users` 非空”的启动自检项，避免此类问题在上线演示时才暴露。可考虑实现 `CampusAuthStartupValidator` 中已有的校验扩展位——当 `campus-auth.enabled=true` 且平台用户表为空时，启动阶段直接 WARN 或拒绝启动。
2. **改善错误提示的用户体验**：当前失败时浏览器直接显示 JSON 报文，普通用户无法理解。建议回调失败时重定向回 `/admin/login` 并以页面提示展示错误码含义。
3. **`path` 字段是误导项**：建议让 `SecurityExceptionHandler.getCurrentPath()` 真正从 `ServerWebExchange` 取路径（WebFlux 下可通过 `exchange.getRequest().getPath().value()`）。否则后续排查都会被误导到 `/api/security`。
4. **管理后台权限单独确认**：`CAMPUS_ADMIN_ACCOUNTS` 为空时，所有 CAS 用户都只有 `USER` 角色。上线前需明确管理员名单维护责任方。

---

## 附：涉及文件索引

| 用途 | 文件 |
|---|---|
| CAS 登录/回调/会话端点 | `src/main/java/org/unreal/modelrouter/auth/campus/controller/CasAuthenticationController.java` |
| 身份映射（报错点） | `src/main/java/org/unreal/modelrouter/auth/campus/service/CampusIdentityService.java` |
| CAS 协议客户端 | `src/main/java/org/unreal/modelrouter/auth/campus/service/CasProtocolClient.java` |
| 平台用户仓储 | `src/main/java/org/unreal/modelrouter/persistence/jpa/repository/platform/PlatformSystemUserRepository.java` |
| 平台用户实体 | `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformSystemUserEntity.java` |
| 实名校验 | `src/main/java/org/unreal/modelrouter/auth/security/util/RealNameAuthUtils.java` |
| 权限配置 | `src/main/java/org/unreal/modelrouter/auth/security/config/SecurityConfiguration.java` |
| 异常到响应 | `src/main/java/org/unreal/modelrouter/common/exceptionhandler/SecurityExceptionHandler.java` |
| CAS 配置 | `src/main/resources/config/auth/campus-auth.yml`、`.env` |
| 前端登录页 | `frontend/src/views/Login.vue`、`frontend/src/stores/user.ts` |
| 生产身份匹配规则 | `docs/zh/deployment/production-readiness.md`（第 98-105 行） |
