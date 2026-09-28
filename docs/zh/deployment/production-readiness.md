# 全校生产上线准备手册

本文是全校分布式部署方案的执行清单。仓库已提供 PostgreSQL/Flyway、学校 CAS 2.0、Redis WebSession、师生分层配额、教师专属入口、自动扩缩、监控告警和压测资产；学校侧仍需提供真实身份属性、基础设施地址、密钥和容量目标。

## 1. 已落地的生产能力

| 能力 | 实现 |
|---|---|
| 数据库变更治理 | Flyway；历史数据库基线为 V8，V9 为集群准备，V10 为校园 CAS 身份迁移 |
| Schema 安全 | 正常副本固定 `ddl-auto=validate`，禁止多副本自动改表 |
| 统一身份 | CAS 2.0 `login` + `serviceValidate`，应用侧 Session 和 RBAC |
| 教师访问 | CAS + 实名平台用户 + 专属入口 + 短令牌，可配置 mTLS 设备绑定 |
| 学生访问 | CAS WebSession，仅开放试验场和个人信息 |
| 管理员访问 | CAS 账号白名单附加 ADMIN；保留受开关和可信 CIDR 限制的本地 JWT 应急入口 |
| 师生配额 | STUDENT/STAFF/TEACHER 月度额度，幂等初始化和单 Worker 月初重置 |
| 管理预置 | `/api/admin/quotas/provision` 和 `/provision-batch`，仅 ADMIN |
| 高可用 | 3–12 个 API Pod、单 Worker、PDB、HPA、反亲和和优雅停机 |
| 可观测性 | Prometheus ServiceMonitor、核心告警、Actuator readiness/liveness |
| 密钥管理 | External Secrets Operator 模板，不要求明文 Secret 入库 |
| 容量验收 | k6 阶梯到达率压测脚本及明确阈值 |

## 2. 学校必须提供的参数

上线前由身份、数据库、网络和运维团队共同确认：

- CAS 根地址（当前资料为 `https://cas.ntit.edu.cn/cas/`）；
- 精确登记的公网回调地址，例如 `https://ai.example.edu/api/auth/cas/callback`；
- 教师、学生的真实 `typeCode` 值和完整成功响应样例；
- `account`、`localAccount`、姓名、部门、`typeCode`、`typeName` 的实际字段名及稳定性；
- CAS 注销后 `service` 回跳规则、测试账号及联调窗口；
- PostgreSQL 高可用 JDBC URL、账号、密码和 CA；
- Redis 主节点/代理地址，或 Sentinel 节点、主节点名及密码；
- 公网域名、TLS 证书、学校 CA/受管设备客户端证书；
- Vault/Secret Manager 路径、告警接收人和容量目标。

所有随机密钥至少使用 32 个随机字节。生产环境不得沿用示例值。管理员账号白名单不得提交到仓库。

## 3. 数据库首次上线

### 3.1 新建空数据库

当前完整历史 Schema 来自 2.6.11 JPA 模型，首次只允许在空库中由单个 bootstrap Job 创建。随后 Flyway 将数据库标记为 V8，并执行 V9、V10；正常 API/Worker 只运行 `validate`。

```bash
kubectl apply -f deploy/kubernetes/namespace.yaml
kubectl apply -f deploy/kubernetes/secret.yaml
kubectl apply -f deploy/kubernetes/database-bootstrap-job.yaml
kubectl -n ai-gateway wait --for=condition=complete \
  job/ai-gateway-database-bootstrap --timeout=10m
kubectl apply -f deploy/kubernetes/ai-gateway.yaml
```

完成后检查：

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

结果应包含 V8 baseline、`9 / cluster production readiness` 和 `10 / campus cas identity`。

### 3.2 已有数据库

1. 先完成全量备份和恢复验证；
2. 检查 `config_data` 是否存在重复 `(config_key, version)`；
3. 检查 `ai_user_free_quota` 是否有非法负数或重复 `user_id`；
4. 检查旧 `teacher_identity_binding`、`teacher_trusted_device`、`teacher_endpoint` 数据和外键；
5. 用同版本镜像在数据库副本上启动，启用 Flyway、保持 Hibernate `validate`；
6. 验证 V9、V10 后再发布生产副本。

V10 会将旧教师 OIDC 身份表泛化为 `campus_identity_binding`，并把设备和 endpoint 外键迁移到新表。证书指纹允许为空，但非空指纹仍保持唯一。禁止在正常 Deployment 中使用 `ddl-auto=update`，也禁止手工修改 `flyway_schema_history`。

## 4. CAS 与角色映射

生产 K8s 使用 `standalone,cluster,cas` Profile。需登记的 CAS `service` 是：

```text
https://ai.example.edu/api/auth/cas/callback
```

应用始终使用 `CAMPUS_PUBLIC_BASE_URL + /api/auth/cas/callback` 生成 `service`，不读取请求的 `Host` 或转发头。登录跳转和 `serviceValidate` 校验必须使用完全相同的 `service` 值。

核心配置示例：

```text
CAMPUS_AUTH_ENABLED=true
CAMPUS_CAS_BASE_URL=https://cas.ntit.edu.cn/cas/
CAMPUS_PUBLIC_BASE_URL=https://ai.example.edu
CAMPUS_SESSION_STORE_TYPE=redis
CAMPUS_SESSION_TIMEOUT=30m
CAMPUS_SESSION_COOKIE_SECURE=true
CAMPUS_ADMIN_ACCOUNTS=<外部密钥系统提供的账号列表>
```

CAS 登录不依赖 `typeCode`。已匹配平台实名用户的 CAS 账号统一获得普通 `USER` 权限；管理员权限只能由 `CAMPUS_ADMIN_ACCOUNTS` 白名单附加。

身份匹配固定为：

1. 优先使用 CAS `localAccount`；
2. 缺失时使用 CAS `account`；
3. 使用该值匹配 `sldd_system_users.user_id`；
4. 本地用户不存在、未实名或状态无效时拒绝登录，不自动创建平台用户。

登录 `WebSession` 必须存入共享 Redis，不依赖 Ingress 会话粘滞；Session Cookie 为 `Secure` + `HttpOnly` + `SameSite=Lax`，默认 30 分钟。浏览器写请求使用 CSRF Cookie/Header；API Key、JWT 和教师短令牌调用保持无状态认证。

预生产必须覆盖登录、退出、角色撤销、禁用账号、字段缺失、未知 `typeCode`、回调地址错误、跨 Pod Session 恢复和 Redis 切换。

## 5. 师生月度配额

默认值：

| 身份 | 每月 Token |
|---|---:|
| 学生 STUDENT | 1,000,000 |
| 教职工 STAFF | 3,000,000 |
| 教师 TEACHER | 5,000,000 |

通过环境变量调整：

```text
STUDENT_MONTHLY_TOKEN_QUOTA
STAFF_MONTHLY_TOKEN_QUOTA
TEACHER_MONTHLY_TOKEN_QUOTA
QUOTA_TIME_ZONE
```

一个用户拥有多个角色时取额度最高的角色。首次 CAS 登录或教师绑定会幂等创建额度；批量导入可调用：

```http
POST /api/admin/quotas/provision-batch
Authorization: Bearer <ADMIN JWT>
Content-Type: application/json

[
  {"userId":"20260001","roles":["STUDENT"]},
  {"userId":"T1001","roles":["TEACHER"]}
]
```

单批最多 1000 人。月初重置仅由 Worker 执行，API Pod 的调度保持关闭。

## 6. 教师设备模式

教师专属 endpoint 和 `ntit_at_` 短令牌继续保留，通过 `TEACHER_REQUIRE_CLIENT_CERTIFICATE` 选择模式：

- `true`：客户端证书指纹、设备和令牌三方绑定，令牌包含证书确认声明；
- `false`：设备记录不要求证书，令牌按教师、endpoint 和有效设备绑定，不写入证书确认声明。

从开启模式切换到关闭模式前，应先确认学校安全策略。重新开启 mTLS 后，无指纹设备必须重新绑定证书。

## 7. PostgreSQL 与 Redis 高可用

生产环境不使用 Compose 内置单节点数据库。推荐使用学校已有托管 PostgreSQL/Redis，或由数据库团队独立运维主备集群。

PostgreSQL JDBC URL 示例：

```text
jdbc:postgresql://pg1.example.edu:5432,pg2.example.edu:5432/ai_gateway?targetServerType=primary&loadBalanceHosts=true&sslmode=verify-full&currentSchema=public&stringtype=unspecified
```

将完整地址写入 `PG_JDBC_URL`。连接池总量按以下公式验收：

```text
API 最大副本数 × PG_POOL_MAX + Worker PG_POOL_MAX + 运维余量 < PostgreSQL max_connections
```

Redis 可以使用提供稳定主节点地址的托管代理；使用 Sentinel 时追加 `redis-sentinel` Profile，并设置 `REDIS_SENTINEL_MASTER`、`REDIS_SENTINEL_NODES` 和密码。

灾备最低要求：PostgreSQL 每日全量备份 + WAL 连续归档，Redis 开启 AOF，季度进行隔离恢复演练。RPO/RTO 由学校审批后写入值班手册。

## 8. 密钥与网络

优先应用 `external-secret.example.yaml` 对接学校 Vault；没有 External Secrets Operator 时才由流水线生成 Kubernetes Secret。不得提交 `secret.yaml`。

网络边界：

- 公网只开放 Ingress 443；
- PostgreSQL、Redis、模型服务不暴露公网；
- Actuator/Prometheus 只允许监控命名空间访问；
- 管理接口仅允许运维网/VPN；
- CAS 回调地址必须由 `CAMPUS_PUBLIC_BASE_URL` 固定生成；
- CAS 客户端使用 JVM 正常证书链和主机名校验，禁止 trust-all；
- 教师 mTLS 在应用端终止，或使用经过签名验证的代理证书证明；
- `/login/emergency` 不在正常登录页展示，后端同时校验启用开关、可信 CIDR、限流和审计。

## 9. 监控与告警

安装 kube-prometheus-stack 后应用：

```bash
kubectl apply -f deploy/kubernetes/production-addons.yaml
```

该清单提供 HPA、ServiceMonitor 和 PrometheusRule。至少接收以下告警：可用副本少于 2、5xx 超过 5%、P95 超过 10 秒、JVM 堆超过 85%、CAS 超时/失败率异常和 Redis Session 异常。

`network-policy.example.yaml` 只是模板。须先根据集群 CNI 确认 kubelet 探针流量的来源和命名空间标签，否则不得直接应用，避免存活/就绪探针被误拦截。

## 10. 压测与验收

先对预生产环境执行模型列表基线压测：

```bash
k6 run \
  -e BASE_URL=https://ai-staging.example.edu \
  -e API_KEY=sk-test-user-key \
  -e TARGET_RPS=100 \
  -e HOLD_DURATION=30m \
  tests/load/k6/campus-gateway.js
```

默认门槛：错误率小于 1%，P95 小于 2 秒，P99 小于 5 秒。模型推理接口需另做流式长连接、图片/视频和上游故障压测，目标并发至少为预计峰值的 1.5 倍。

必须演练：CAS 正常登录与注销、ticket/state 异常、开放重定向、恶意 XML、Session CSRF、跨 Pod Session、隐藏应急登录、教师 mTLS 两种模式、删除一个 API Pod、Worker 重启、Redis 主从切换、PostgreSQL 主备切换、CAS 暂时不可用、配置发布、滚动升级和上一版本回滚。

## 11. 推荐发布顺序

1. 生成不可变镜像并完成漏洞扫描；
2. 在数据库副本验证 V9/V10 Flyway；
3. 向校方登记精确 CAS 回调地址并取得真实 `typeCode` 样例；
4. 配置 ExternalSecret、TLS、CAS、Redis Session、角色映射和管理员白名单；
5. 发布 1 个预生产 API Pod 与 Worker；
6. 完成身份、权限、配额、计费、应急入口和压测验收；
7. 发布 3 个生产 API Pod；
8. 应用 HPA、ServiceMonitor、PrometheusRule，并审核后应用 NetworkPolicy；
9. 观察至少一个完整业务高峰；
10. 通过变更审批后面向全校开放。

生产开放的最终门槛不是“Pod 已运行”，而是数据库可恢复、身份可撤销、CAS 回调已登记、真实角色映射已验证、Session 跨节点一致、告警有人接收、故障切换和回滚均已演练。
