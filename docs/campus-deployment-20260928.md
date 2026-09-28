# 校园自助工作台上线记录 — 2026-09-28

> 安全更新：后续发布继续要求旧网关 Key 失效。不得使用本记录最初上线前的 legacy 镜像回滚；以 `docs/model-square-deployment-20260928.md` 中兼容回滚为准。

- 状态：已上线，容器健康检查 healthy，应用健康 UP。
- 公网入口：https://gordanshop.com/admin/me
- 发布镜像：sha256:5a9d1d9adbb6257ecfb8f74d1026f42414b2afd5a00a981a691e98854a8fd5b5
- 发布标签：ai-gateway-standalone:release-20260928-095501-r2（local 指向相同镜像）。
- 最终镜像启动时间（UTC）：2026-09-28T01:04:12.87908673Z
- 用户已明确同意旧网关本地 Key 立即失效。

## 执行过程

1. 为旧镜像保存 rollback-20260928-095501 标签。
2. 备份 PostgreSQL、实际运行配置、配置挂载与状态数据；备份目录限制当前 Windows 用户和 SYSTEM 访问。
3. 在无网络/无对外端口的临时 PostgreSQL 中恢复备份并预演 V12，验证成功后移除临时容器。
4. 当前 standalone Compose 默认关闭 Flyway，故 V12 使用 psql 单事务执行，不修改或伪造 Flyway 历史。
5. 重建前端与后端，以仅包含 Dockerfile 和 JAR 的最小上下文构建镜像，仅替换 gateway。
6. 修复上线时发现的反向代理未解析远程地址导致的 SpanAttributeHelper 空指针，增加 IPv4/IPv6 回归测试并部署修复镜像。

## 已验证

- 公网和本地工作台入口、Key/用量/模型/资料页面入口均返回 HTTP 200；公网静态资源引用与新构建一致。
- CAS 登录入口返回 302，目标学校 CAS 主机正确；未登录个人与管理接口返回 401。
- 使用切换前实际配置的旧 Key 请求受保护的 POST /v1/chat/completions，本地及公网都返回 401 / API_KEY_INVALID。
- 注意：GET /v1/models 是公开目录，返回 200 不能证明 Key 认证成功，不能用它验收 Key 失效。
- V12 两张新表及索引创建成功，全局默认 Key 上限为 5，新 Key 初始数量 0；原有 6 条账单及外部 Key 表未删除。
- CAMPUS_UNRESTRICTED_ACCESS=false 已在新容器生效，CAMPUS_DEFAULT_ADMIN=true 保留。
- 前端类型检查及构建通过；后端全量测试 1294 项，0 失败，0 错误，1 项跳过。本次 Maven 跳过 Checkstyle/SpotBugs/JaCoCo，不将其称为已通过的静态检查。
- 修复后重复公网探测，当前容器日志未再出现该链路追踪空指针。
- PostgreSQL、Cloudflare Tunnel、Caddy 和同机其他应用未被停止或重建。

## 未验证与已知风险

- 尚未用实际校园账户完成 CAS 登录 → 创建/保存新 Key → 合法模型调用 → 真实扣费与个人用量更新的端到端流程；未伪造 CAS 会话，未注入假余额，未发起产生费用的测试请求。
- 本实例外部 ai_api_key 表为空，没有可用外部平台 Key，因此本次不能宣称完成外部 Key 生产调用验证。
- 所有校园用户仍为全局管理员，个人视图不是强访问隔离，数量上限可经管理入口绕过。
- 启动检查提示现有 JWT 密钥及初始管理员密码强度不足；未擅自更换，建议独立安排凭据轮换。

## 备份与回滚

- 备份目录：D:\Code\Python\AI网关\南通算力平台代码\ai-gateway-standalone\target\release-backups\20260928-095501
- 目录中包含 database.dump、恢复清单、受保护的 rollback.compose.json、rollback-gateway.ps1、HTTP 验证结果与后端测试日志。
- 回退旧镜像会重新接受旧本地 Key，且不支持 gw2 Key；执行回退脚本必须显式传入 -ConfirmLegacyKeyReactivation 并先通知调用方。
- 回退覆盖配置仍保持 CAMPUS_UNRESTRICTED_ACCESS=false，不自动回滚数据库或删除 V12 表。

## 个人工作台接口修复 — 2026-09-28 10:33:22 +09:00

- 状态：修复已发布，网关容器 healthy，本地应用及数据库健康 UP。仅替换 gateway，其余容器保持运行。
- 根因：CampusPrincipal 实现 java.security.Principal，WebFlux 内建 Principal 解析器提前匹配控制器参数，传入 CampusAuthentication，导致个人资料、Key、余额和用量接口发生类型不匹配；不是余额缺失或数据库结构异常。
- 修复：个人接口统一接收 Authentication，校验已认证 CampusAuthentication 后明确提取其 CampusPrincipal；继续只使用会话用户 ID。未更改用户余额、实名状态、额度、Key 上限和全员 ADMIN 设定。
- 回归：专项 HTTP/身份测试 12 项通过；后端全量测试 1301 项，0 失败、0 错误、1 跳过，package 成功。本轮未变更前端，未重复前端构建；Checkstyle、SpotBugs、JaCoCo 跳过。
- 镜像：sha256:4a9b2f6a44f6ad3445751bdd5a72d2ca79328e0526ac2ae5bc98880790030bbd
- 发布标签：ai-gateway-standalone:fix-self-service-20260928-103029
- 验证：本地和公网工作台入口 HTTP 200；未登录 profile、keys、balance、usage 均 401；正确 CAS 登录接口 /api/auth/cas/login 返回 302 至学校 CAS。公网 /actuator/health 被 Caddy 按既有策略隐藏（404），本地健康接口 200 / UP。
- 旧配置 Key 请求受保护 POST /v1/chat/completions 在本地和公网仍为 401 / API_KEY_INVALID；CAMPUS_UNRESTRICTED_ACCESS=false，CAMPUS_DEFAULT_ADMIN=true。
- 真实账户验收限制：当前浏览器无可用标签及校园会话，未完成生产校园用户三个页面的登录后端到端验证，未创建真实 Key 或产生调用费用。会话使用内存存储，网关重启后需重新校园登录。
- 本轮发布记录与测试日志：D:\Code\Python\AI网关\南通算力平台代码\ai-gateway-standalone\target\self-service-fix-20260928-103029
- 本轮回滚镜像：ai-gateway-standalone:rollback-self-service-20260928-103029（上一版 r2，仍拒绝旧本地 Key）。rollback.compose.json 只覆盖 gateway 镜像，不回滚数据库；不要使用原始上线前的旧镜像回滚，以免重新启用旧 Key。
