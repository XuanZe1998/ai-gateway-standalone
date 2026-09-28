# 教师统一认证与受管设备绑定

## 安全边界

教师通过学校 OIDC 登录后，系统把 OIDC 主体、算力平台实名用户、教师专属 URL、短期访问令牌和受管设备客户端证书绑定。复制 URL 和令牌到其他设备时，因没有不可导出的设备私钥，请求会被拒绝。

这一方案防止“凭据被拷贝到另一台设备”，不防止他人直接操作教师已解锁的受管电脑。如需防止后者，本地代理需在签发/刷新令牌时要求 Windows Hello 用户在场验证。

## 请求链路

1. 教师通过 `/oauth2/authorization/ntit` 登录。
2. 系统校验教师角色，按 `system_user_id` 或工号关联平台实名用户。
3. 受管设备使用 mTLS 调用 `POST /api/teacher/devices/bind`。
4. `GET /api/teacher/access-profile` 返回专属 `baseUrl` 和 5 分钟的 `ntit_at_` 令牌。
5. 模型请求使用 `https://.../u/{endpointId}/v1`；服务端同时校验 URL、令牌 `cnf` 指纹和 TLS 客户端证书。
6. 设备被撤销后，即使旧令牌未到期，下一次请求也会立即失效。

## 启用步骤

1. 审核并执行 [V8__teacher_device_bound_access.sql](../src/main/resources/db/scripts/V8__teacher_device_bound_access.sql)。Standalone 环境的 `ddl-auto: update` 会自动创建表，生产环境应手工审批。
2. 复制 `config-external/auth/teacher-access.example.yml` 为 `config-external/auth/teacher-access.yml`。
3. 在 `.env` 中填写 OIDC issuer/client id/client secret，以及独立的 32 字节以上 `TEACHER_TOKEN_SECRET`。
4. 确认 IdP 返回工号、用户名和教师角色 claim，必要时在模板中修改 claim 名称。
5. 在学校 CA/MDM 中为受管设备签发不可导出的 TPM 客户端证书，网关 TLS 配置为 `client-auth: need`。
6. 确认回调地址是 `https://<域名>/login/oauth2/code/ntit`，再将 `TEACHER_ACCESS_ENABLED` 改为 `true` 并重启。

## mTLS 部署要求

当前实现只信任 WebFlux TLS 连接中已校验的 peer certificate，不信任普通 HTTP 头传入的证书指纹。因此应使用以下两种方式之一：

- Spring Boot/Netty 直接终止 mTLS，并配置服务器 keystore 和学校 CA truststore。
- 后续在受信反向代理与应用之间增加签名的证书证明协议；未实现前不得直接信任 `X-Client-Cert` 一类外部可伪造头。

## 上线前必测

- 正确教师 + 正确专属 URL + 正确设备证书可调用。
- 复制 URL/令牌到无证书设备返回 `DEVICE_CERT_REQUIRED`。
- 使用另一台受管设备的证书返回 `TOKEN_DEVICE_MISMATCH`。
- 使用其他教师的专属 URL 返回 `ENDPOINT_OWNER_MISMATCH`。
- 撤销设备后返回 `TEACHER_DEVICE_REVOKED`。
- 非教师角色返回 `TEACHER_ROLE_REQUIRED`。
