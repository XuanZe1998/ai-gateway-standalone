# 独立模型广场发布记录 — 2026-09-28

## 发布状态

- 已上线：2026-09-28T13:00:42.042319+09:00；网关 healthy，本地应用与数据库健康 UP。
- 用户入口：`https://gordanshop.com/admin/models`。
- 内容管理：`https://gordanshop.com/admin/system/model-square`。
- 原 `/admin/me/models` 由前端重定向至独立广场；试验场继续使用原 `/api/models` 目录接口。
- 发布标签：`ai-gateway-standalone:model-square-20260928-125056`。
- 镜像：`sha256:f16f2edb81c331784bbece4eafa7bb7111c60da9661c38eaa63bed583f35f428`。
- JAR SHA-256：`4f6c240c9e4714f342a7e9d4404d4b5b6e2f1e25248a0ea0e492fb690603c72d`。
- 网关使用内存会话，发布重启后须重新 CAS 登录。

## 功能与边界

- 按服务类型与实际模型 ID 合并运行中的模型；12 个分页、名称/ID 搜索、服务与厂商筛选、固定分区等高卡片。
- 详细收费复用实际定价缓存与当前认证账户折扣，包括输入/输出、缓存、思考、阶梯与视频条件；缺价不表示免费、多渠道差异以匿名方案分别展示。
- 接入面板提供当前站点 Base URL、真实服务路径/HTTP 方法、模型 ID、cURL；文件上传采用 multipart，视频说明创建与查询。只使用 YOUR_KEY，不自动创建 Key 或调用模型。
- 管理员可修改展示名、简介、标签和恢复平台默认，不修改真实模型 ID 或计费配置；新 V13 表保存内容覆盖。
- 登录接口使用 DTO 白名单且 no-store；不返回上游 URL、凭据、实例配置或渠道 ID。
- 继续保留全员 ADMIN；不宣称具有专属运营权限隔离。真实计费、免费额度与 Key 授权规则未修改。

## 数据库与发布

1. 备份 PostgreSQL、自有部署配置和当前仍拒绝旧 Key 的网关镜像；备份目录仅本机操作用户、SYSTEM、Administrators 可访问，包含秘密，不上传。
2. 在独立 loopback PostgreSQL 16.15 中恢复备份成功，演练 V13 后使用真实 JPA 完成保存、读取、更新、删除测试。
3. Flyway 仍关闭。生产 V13 以 `psql -v ON_ERROR_STOP=1 --single-transaction` 显式执行，不修改 Flyway history。
4. 验证 V13 字段与唯一约束，迁移前后账单/校园 Key/外部 Key 表行数一致；未向生产写入本地测试模型或内容。
5. 以仅包含 Dockerfile 与 JAR 的最小上下文构建，Compose 使用 `up -d --no-deps --no-build gateway` 仅替换网关。
6. PostgreSQL、Caddy、Cloudflare 与其他共 20 个生产容器 ID/StartedAt 不变。

## 验证

- 前端类型检查、构建成功；模型广场辅助函数测试 12/12 通过。
- 后端全量测试：1322 项，0 失败、0 错误、2 跳过（包含默认跳过的隔离 PostgreSQL opt-in 测试）。另行开启该持久化测试，1/1 通过。
- Checkstyle/SpotBugs/JaCoCo 按当前构建流程跳过，未称其通过。
- 本地内存测试页验证搜索、服务/厂商筛选、分页、空结果、列表故障重试、复制、管理员保存/回显/恢复确认、视频创建与查询示例；375px/768px/桌面布局无页面横向溢出，手机抽屉全屏、收费表内部滚动。
- 本地及公网共 30 项 HTTP 检查通过：新旧入口与试验场 SPA 正常，新静态资源匹配构建；匿名广场/内容管理/个人接口 401；CAS 登录正确 302 至学校。
- 切换前配置旧 Key 请求受保护的 POST `/v1/chat/completions`（不存在的模型探针，未产生付费调用），本地及公网均 401/API_KEY_INVALID。
- `CAMPUS_UNRESTRICTED_ACCESS=false`、`CAMPUS_DEFAULT_ADMIN=true`。
- 浏览器验证生产广场入口正常显示学校统一登录保护。公网 health 仍按现有 Caddy 策略隐藏，本地 health UP。
- Cloudflare 对默认 Python urllib User-Agent 返回 1010，使用正常 curl 客户端验证通过；未修改 Cloudflare、网络安全策略或凭据。

## 未完成的真实账户验收

当前没有可用生产校园会话，未完成真实账户登录后的模型列表/内容保存及 Key 调用→扣费→个人用量更新端到端测试。未伪造校园会话，未创建真实 Key，未调用有效模型；本地 UI 价格与模型均明确为测试数据，不是线上报价。需要校园用户重新登录后验收。外部平台 Key 认证逻辑保持原样，未发起外部生产 Key 付费验证。

## 备份与安全回滚

- 受保护目录：`D:\Code\Python\AI网关\南通算力平台代码\ai-gateway-standalone\target\model-square-release-20260928-125056`。
- 记录：database.dump、manifest.json、迁移校验、HTTP 结果、隔离持久化测试日志，以及安全 rollback-gateway.ps1。
- 唯一建议回滚标签：`ai-gateway-standalone:rollback-model-square-20260928-125056`，镜像 `sha256:4a9b2f6a44f6ad3445751bdd5a72d2ca79328e0526ac2ae5bc98880790030bbd`，保留上一轮个人接口修复且仍拒绝旧 Key。
- 用目录内 `rollback-gateway.ps1` 或其 rollback.compose.json 仅替换 gateway；保持 unrestricted=false，保留 V13 表，不回滚数据库，不重启其他服务。
- **禁止使用最初上线前的 legacy 镜像或恢复旧认证配置回滚**，以免重新接受旧 Key。原 09:55 发布记录中的 legacy 回滚说明不适用于本次或今后发布。
- 回滚后需检查 gateway healthy、CAS 登录及受保护 POST 的旧 Key 拒绝结果；用户需要重新登录。
