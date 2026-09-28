# 南通理工学院统一身份认证 CAS 对接资料整理

## 1 文档用途

本目录保存学校统一门户的 CAS 单点登录对接说明及 Java JSP、ASP.NET、PHP 三套示例代码。

本资料采用的是 **CAS 2.0 单页面接入模式**：业务系统新增一个 `sso/login` 入口，由该入口负责跳转学校 CAS、接收票据、校验票据并把用户写入业务系统会话。原业务系统的用户、角色和权限逻辑仍由业务系统实现。

> 原始示例代码年代较早，包含测试地址、占位地址和不安全的 TLS 证书处理。请勿原样部署到生产环境。

## 2 学校 CAS 地址

来源文件：`南通理工统一身份认证系统登录地址.txt`

| 项目 | 地址 |
| --- | --- |
| CAS 根地址 | `https://cas.ntit.edu.cn/cas/` |
| 登录地址 | `https://cas.ntit.edu.cn/cas/login` |
| 票据校验地址 | `https://cas.ntit.edu.cn/cas/serviceValidate` |
| 注销地址 | `https://cas.ntit.edu.cn/cas/logout` |

业务系统还需要确定自己的外部访问地址，例如：

```text
https://业务系统域名/上下文路径/sso/login.jsp
```

该地址会作为 CAS 的 `service` 参数。登录和票据校验时的 `service` 必须保持完全一致，包括协议、域名、端口、路径和查询参数。

## 3 目录说明

```text
PC端V6统一身份认证对接文档/
├─ README.md                              本整理说明
├─ 统一身份认证对接实施检查清单.md          实施和验收清单
├─ 南通理工统一身份认证系统登录地址.txt      学校 CAS 登录地址
├─ 业务系统CAS认证集成(单页面方式)说明文档.docx
├─ JAVA-JSP/
│  └─ sso/                                JSP 示例及 CAS 工具代码
├─ Dotnet/
│  ├─ Bin/                                已编译 DLL
│  ├─ com.kingstar.sso.client.single.dll源码/ DLL 源码
│  └─ sso/                                ASP.NET Web Forms 示例
└─ PHP/
   └─ sso/                                PHP 示例及旧版 phpCAS 依赖
```

建议保持示例目录中的相对路径不变，因为登录、注销、欢迎页和工具代码之间存在相对引用。

## 4 认证流程

1. 用户访问业务系统的 SSO 登录入口。
2. SSO 登录入口检查业务系统 Session。
3. 未登录且请求中没有 `ticket` 时，重定向到学校 CAS 登录地址，并携带业务系统 `service` 回调地址。
4. 用户在学校统一认证页面完成登录。
5. CAS 携带一次性 `ticket` 回调业务系统的 SSO 登录入口。
6. 业务系统使用同一个 `service` 和收到的 `ticket` 请求 `/serviceValidate`。
7. 校验成功后得到 CAS 账号及用户属性。
8. 业务系统执行自己实现的 `doLogin`：匹配本地用户、检查状态、加载角色权限并建立业务 Session。
9. 登录成功后跳转到 `targetUrl` 或默认首页。
10. 注销时先清理本地 Session，再跳转 CAS 注销地址。

## 5 必须修改的配置

### 5.1 Java JSP

文件：`JAVA-JSP/sso/supwisdom/Constants.jsp`

```java
String CAS_BASE_PATH = "https://cas.ntit.edu.cn/cas/";
String CLIENT_SYSTEM_EXPLICIT_PORT = "";
String DEF_TARGET_URI = "sso/index.jsp";
String SSO_LOGIN_URI = "sso/login.jsp";
```

说明：

- 示例中的 `https://ip:port/cas/` 是占位值，必须替换。
- `CLIENT_SYSTEM_EXPLICIT_PORT` 是业务系统对外端口，不是 CAS 的端口。标准 HTTPS 一般留空。
- 在 `JAVA-JSP/sso/login.jsp` 中实现 `doLogin(LoginUser, HttpServletRequest)`。
- 在 `JAVA-JSP/sso/logout.jsp` 中实现本地注销逻辑。

### 5.2 ASP.NET Web Forms

文件：`Dotnet/sso/Web.config`

```xml
<add key="CAS_BASE_PATH" value="https://cas.ntit.edu.cn/cas/"/>
<add key="DEF_TARGET_URI" value="sso/index.aspx"/>
<add key="SSO_LOGIN_URI" value="sso/login.aspx"/>
<add key="CLIENT_SYSTEM_EXPLICIT_PORT" value=""/>
```

说明：

- 原文件中的 `http://app.supwisdom.com:7879/cas/` 是厂商测试地址，必须替换。
- 把 `Dotnet/Bin/com.kingstar.sso.client.single.dll` 放入站点 `bin` 目录。
- 在 `Dotnet/sso/login.aspx` 中实现 `doLogin`。
- 在 `Dotnet/sso/logout.aspx` 中实现本地注销逻辑。

### 5.3 PHP

文件：`PHP/sso/supwisdom/Config.php`

```php
const CAS_HOST = 'cas.ntit.edu.cn';
const CAS_CONTEXT = '/cas';
const CAS_PORT = 443;
```

同时需要处理：

1. `PHP/sso/supwisdom/CAS/Client.php` 第 317 行当前硬编码为 `http://`，按原说明需改为 `https://`。
2. 在 `PHP/sso/login.php` 中实现 `doLogin(array $loginUser)`。
3. `PHP/sso/supwisdom/Init.php` 中的 `BASH_PATH` 实际表示业务系统基础地址，需确认反向代理环境下生成的是外部 HTTPS 地址。
4. 示例调用了 `phpCAS::setNoCasServerValidation()`，生产环境应改为校验证书链，不应关闭 CAS 服务端证书验证。

## 6 doLogin 应实现的业务逻辑

三套示例中的 `doLogin` 都只是占位实现。正式接入至少应完成：

1. 检查 CAS 主账号 `account` 是否存在。
2. 根据 `localAccount` 或 `account` 查找业务系统本地用户。
3. 明确账号映射优先级，建议：有效的 `localAccount` 优先，否则使用 `account`。
4. 检查本地用户是否存在、是否启用、是否被锁定、是否允许访问当前系统。
5. 加载用户角色、部门、数据权限和菜单权限。
6. 重新生成业务系统 Session ID，防止会话固定攻击。
7. 只向 Session 保存业务需要的字段，避免保存身份证号等非必要敏感信息。
8. 登录成功返回 `true`；任何校验失败均返回 `false` 并记录可审计日志。
9. 日志不得记录 CAS ticket、身份证号、手机号等完整敏感数据。

## 7 CAS 返回的用户字段

| 字段或方法 | 含义 | 建议 |
| --- | --- | --- |
| `account` / `getAccount()` | 统一认证主账号 | 必需，作为认证成功的核心标识 |
| `ssoAccount` / `getSsoAccount()` | 统一认证账号 | 文档说明通常与 `account` 相同 |
| `localAccount` / `getLocalAccount()` | 业务系统本地账号 | CAS 维护账号映射时使用 |
| `name` / `getName()` | 姓名 | 展示用途，不宜作为唯一键 |
| `deptCode` / `getDeptCode()` | 部门代码 | 可用于组织映射 |
| `deptName` / `getDeptName()` | 部门名称 | 展示用途 |
| `dn` / `getDn()` | 组织机构名称 | 需以实际返回值为准 |
| `typeCode` / `getTypeCode()` | 身份类型代码 | Java 示例提供；需确认实际返回 |
| `typeName` / `getTypeName()` | 身份类型名称 | Java 示例提供；需确认实际返回 |
| `email` / `getEmail()` | 邮箱 | 可选 |
| `mobile` / `getMobile()` | 手机号 | 敏感字段，按需使用 |
| `tel` / `getTel()` | 电话 | 敏感字段，按需使用 |
| `idCard` / `getIdCard()` | 身份证号码 | 高敏感字段，原则上不落库、不记日志 |
| `nick` / `getNick()` | 昵称 | 可选 |
| `remark` / `getRemark()` | 备注 | 可选 |
| `studentNo` | 扩展字段 | 原文标记为已弃用 |
| `staffNo` | 扩展字段 | 原文标记为已弃用 |
| `dicOrgId` | 扩展字段 | 原文标记为已弃用 |
| `type` | 扩展字段 | 原文标记为已弃用 |

字段是否返回取决于学校 CAS 的实际配置。正式编码时必须允许非必需字段为空。

## 8 targetUrl 使用方式

示例支持：

```text
https://业务系统域名/上下文路径/sso/login.jsp?targetUrl=https://业务系统域名/目标页面
```

Java 和 .NET 示例在与 CAS 交互时，会把目标地址编码为以 `base64` 开头的参数；回调后再解码。

### 安全要求

原示例会直接跳转请求传入的 `targetUrl`，存在开放重定向风险。正式实现必须满足以下一种策略：

- 只允许站内相对路径，例如 `/console/home`；或
- 对完整 URL 校验协议、域名、端口和路径白名单。

禁止跳转到用户任意指定的外部域名，也不要把 Base64 当作安全校验手段。

## 9 反向代理和 HTTPS 注意事项

若业务系统部署在 Nginx、负载均衡或网关之后，需要确保应用生成的回调地址是浏览器实际访问的外部地址：

- 外部使用 HTTPS 时，`service` 不能生成成 HTTP。
- 外部为标准 443 端口时，不应错误携带内部容器端口。
- 正确传递并可信处理 `X-Forwarded-Proto`、`X-Forwarded-Host`、`X-Forwarded-Port`。
- 推荐配置固定的业务系统外部基础 URL，不完全依赖请求头动态拼接。
- 学校 CAS 端通常需要登记允许访问的 `service` 地址或地址前缀，需向校方确认。

## 10 原始示例中发现的风险和兼容性问题

### 高优先级

1. **Java 跳过 TLS 校验**：`CommonUtils.jsp` 的 `trustEveryone()` 接受任意主机名和任意证书。
2. **.NET 跳过 TLS 校验**：DLL 源码中的 `CheckValidationResult` 始终返回 `true`，并全局设置证书验证回调。
3. **PHP 跳过 TLS 校验**：`Init.php` 调用了 `setNoCasServerValidation()`。
4. **开放重定向**：三套示例均需对 `targetUrl` 做站内或白名单校验。
5. **示例配置不是学校生产配置**：.NET 和 PHP 仍指向厂商测试主机，Java 仍是占位地址。

上述 TLS 绕过代码不应直接进入生产环境，应改为使用操作系统或 JVM 的受信任证书链，必要时导入学校提供的 CA 证书。

### 需要联调确认

1. .NET 源码通过 `attributes["字段名"]` 直接取值，如果 CAS 未返回某个可选字段会抛出异常；应改为安全取值。
2. .NET 的 `LoginUser` 没有 Java 示例中的 `typeCode`、`type`、`typeName` 字段，若系统需要身份类型，应确认 DLL 版本。
3. PHP 首页把 `account` 显示为“姓名”，这与字段说明不一致，展示代码需要调整。
4. Java 示例会把完整 CAS XML 打印到标准输出，可能泄露个人信息，生产环境应删除。
5. 示例依赖和页面技术较旧，接入现代框架时建议按 CAS 2.0 协议重新封装，而不是复制 JSP/ASPX/PHP 页面代码。

## 11 推荐实施顺序

1. 向学校确认 CAS 协议版本、服务注册地址规则、测试账号和实际返回属性。
2. 确定业务系统对外 HTTPS 基础地址和 SSO 回调地址。
3. 先在独立测试环境跑通登录、票据校验、属性获取和注销。
4. 实现业务系统 `doLogin` 和本地账号映射。
5. 增加 `targetUrl` 白名单、TLS 校验、Session 安全和日志脱敏。
6. 测试未登录、已登录、票据无效、用户不存在、用户禁用、重复回调和注销等场景。
7. 完成校方联调和生产服务地址登记后再上线。

## 12 最小验收结果

接入完成后，应能够证明：

- 未登录用户会被正确引导到学校统一登录页。
- CAS 回调后可通过服务端校验 ticket，不能只相信浏览器参数。
- 能稳定获取 `account`，并正确映射本地用户。
- 用户角色和权限来自本业务系统，未因 SSO 绕过授权校验。
- `targetUrl` 不能跳转到外部恶意网站。
- TLS 证书会被正常验证。
- 注销后本地 Session 失效，并按预期退出或返回统一认证入口。

详细操作项见 `统一身份认证对接实施检查清单.md`。

## 13 本项目接入落点

本仓库已按上述资料重新实现 CAS 2.0 客户端，不直接复制旧 JSP、ASP.NET 或 PHP 示例。主要落点如下：

| 范围 | 项目位置 |
| --- | --- |
| CAS 配置 | `src/main/resources/config/auth/campus-auth.yml`、`src/main/resources/application-cas.yml` |
| CAS 协议与会话端点 | `src/main/java/org/unreal/modelrouter/auth/campus/` |
| 安全规则与 CSRF | `src/main/java/org/unreal/modelrouter/auth/security/config/SecurityConfiguration.java` |
| 校园身份持久化 | `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/CampusIdentityBindingEntity.java` |
| Flyway 迁移 | `src/main/resources/db/migration/postgresql/V10__campus_cas_identity.sql` |
| 教师设备与短令牌 | `src/main/java/org/unreal/modelrouter/auth/teacher/` |
| 前端登录与门户 | `frontend/src/views/Login.vue`、`AuthCallback.vue`、`TeacherPortal.vue`、`Profile.vue` |
| 前端会话与路由 | `frontend/src/stores/user.ts`、`frontend/src/router/index.ts`、`frontend/src/utils/request.ts` |
| 生产配置模板 | `.env.example`、`.env.cluster.example`、`docker-compose.yml`、`deploy/kubernetes/` |

本项目使用以下入口：

```text
GET  /api/auth/cas/login?target=/admin/auth/callback
GET  /api/auth/cas/callback?ticket=...&state=...
POST /api/auth/cas/slo（学校 CAS 后台单点注销通知）
GET  /api/auth/session
POST /api/auth/cas/logout
```

前端部署基路径为 `/admin/`，因此浏览器回调处理中间页的完整站内路径是 `/admin/auth/callback`，Vue Router 内部路由为 `/auth/callback`。

模板配置中 `CAMPUS_AUTH_ENABLED` 默认为 `false`；当前单机 Tunnel 部署的 `.env` 已开启 CAS，并使用 `https://gordanshop.com` 作为公网基址。正式启用前向校方登记精确回调地址；CAS 登录不依赖 `typeCode`。系统只为已匹配的平台实名用户授予普通 `USER` 权限，不会自动创建 `sldd_system_users` 用户；管理员权限仍由独立白名单控制。学校 CAS 若向原始 `service` 回调地址发送单点注销 POST，应用也在该地址接收同一格式的通知。
