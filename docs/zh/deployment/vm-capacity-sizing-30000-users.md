# AI Gateway 虚拟机配置清单（3 万用户、均衡生产、最小 POC）

> 编制日期：2026-09-21  
> 适用项目：`ai-gateway-standalone`  
> 范围：AI Gateway、负载均衡、PostgreSQL、Redis、Worker、监控日志等公共基础设施。**不包含 GPU 推理服务器及模型授权费用。**

## 1. 容量口径

“3 万人同时使用”必须区分在线人数和正在生成内容的并发请求数。本清单给出三个口径：

1. **3 万严格并发方案**：按 30,000 条同时存在的 Chat/SSE 长连接规划，是成本最高的生产方案；
2. **均衡生产方案**：按 30,000 注册用户、1,000～3,000 高峰在线、300～1,000 条同时流式请求规划，与仓库现有全校部署基线一致；
3. **最小 POC 方案**：用于功能验证和小规模演示，不提供高可用能力。

容量最终必须通过真实模型、真实请求长度和真实认证/计费链路压测定版。建议生产验收按目标并发的 **1.5 倍**持续压测 2 小时。

## 2. 方案 A：30,000 条请求同时使用（严格生产方案）

### 2.1 虚拟机清单

| 角色 | 数量 | 单台配置 | 系统盘/数据盘 | 网络 | 部署说明 |
|---|---:|---|---|---|---|
| 入口负载均衡 LB | 2 | 8 vCPU / 16 GB | 100 GB SSD | 双网卡，10 Gbps | Nginx/HAProxy + Keepalived，主备或双活；TLS 卸载，关闭 SSE 响应缓冲 |
| Gateway API | **8 台起步，可扩至 12 台** | **16 vCPU / 32 GB** | 100 GB 系统盘 + 100 GB 临时日志盘 | 10 Gbps | 每台仅运行 1 个 Gateway 实例；8 台通过压测后承载 30,000 并发，保留扩容到 12 台的地址与配额 |
| Gateway Worker | 2 | 8 vCPU / 16 GB | 100 GB SSD | 1 Gbps | 1 台活动、1 台冷备；同一时间只允许 1 个 Worker 执行业务定时任务 |
| PostgreSQL | 3 | **16 vCPU / 64 GB** | **1 TB NVMe/台** | 10 Gbps | 1 主 2 备，推荐 Patroni/托管 PostgreSQL；开启 WAL 归档和时间点恢复 |
| PgBouncer/DB VIP | 2 | 4 vCPU / 8 GB | 50 GB SSD | 1～10 Gbps | 数据库连接复用与主库入口；也可由托管数据库代理替代 |
| Redis | 3 | **8 vCPU / 32 GB** | 200 GB SSD/台 | 10 Gbps | Sentinel/Cluster 或托管 Redis；用于共享会话、分布式限流和状态数据 |
| 监控与日志 | 3 | **16 vCPU / 64 GB** | 2～4 TB SSD/台 | 10 Gbps | Prometheus/Grafana/日志检索；容量按日志保留期调整，建议业务日志进入独立日志平台 |
| 备份节点/对象存储 | 1 套 | 8 vCPU / 16 GB（如采用 VM） | 8 TB 起，可扩容 | 1～10 Gbps | 保存 PostgreSQL 全量备份、WAL、配置和必要审计归档；不得与生产数据库共盘 |
| 运维堡垒机 | 1 | 4 vCPU / 8 GB | 100 GB SSD | 1 Gbps | 仅运维网/VPN访问，可复用现有学校基础设施 |

### 2.2 Gateway 单机运行参数

建议每台 Gateway VM 给容器分配：

```yaml
resources:
  requests:
    cpu: "8"
    memory: 16Gi
  limits:
    cpu: "14"
    memory: 28Gi
```

JVM 起步参数：

```text
-Xms8g -Xmx20g -XX:+UseG1GC -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError
```

建议操作系统参数：

```text
nofile >= 200000
net.core.somaxconn >= 65535
net.ipv4.ip_local_port_range = 10000 65535
```

LB 必须针对流式响应设置：

- 关闭 `proxy_buffering`；
- 上游读取超时不小于模型最大生成时间，建议从 10～30 分钟起步；
- 保持 HTTP/1.1 keep-alive，若环境验证通过可启用客户端 HTTP/2；
- 单台 LB 文件描述符不低于 200,000；
- 健康检查只转发到 readiness 正常的 Gateway。

### 2.3 容量验收条件

8 台 Gateway 要支撑 30,000 活动长连接，平均每台约 3,750 条；下线 1 台后，剩余每台约 4,286 条。上线前应满足：

- 8 台集群在 45,000 并发下持续 2 小时稳定；
- 30,000 并发时任意下线 1 台 Gateway，整体仍可用；
- 网关自身新增延迟 P95 小于 100 ms，不含模型推理时间；
- 5xx 错误率小于 1%，无持续内存增长、连接泄漏或频繁 Full GC；
- PostgreSQL、Redis 主备切换后自动恢复；
- 限流、配额、会话和计费在所有副本间保持一致。

如果单台在真实请求下无法稳定承载至少 5,625 条连接（45,000 / 8），应扩至 10～12 台，而不是继续提高单机并发。

### 2.4 数据库连接建议

8 台 API Gateway + 1 个活动 Worker，可先设置：

```text
Gateway：PG_POOL_MAX=15，PG_POOL_MIN=3
Worker：PG_POOL_MAX=15，PG_POOL_MIN=3
应用最大连接合计约 135
含 30% 余量及运维连接：PostgreSQL/PgBouncer 按 200～250 个连接规划
```

不要通过无限增大数据库连接池解决吞吐问题。计费、审计集中完成时会形成写入峰值，应重点观察数据库 IOPS、WAL、锁等待和慢 SQL。

### 2.5 模型侧容量提醒

严格 30,000 并发最大的瓶颈通常不是网关，而是模型推理池。例如每条流平均输出 20 token/s，则模型池需要约：

```text
30,000 × 20 token/s = 600,000 输出 token/s
```

因此 GPU/模型 API 必须单独按模型、上下文长度、首 token 延迟和单卡吞吐压测。网关服务器清单不能替代 GPU 容量清单。

## 3. 方案 B：均衡生产配置（推荐常规起步）

适用口径：约 30,000 注册用户、1,000～3,000 高峰在线用户、300～1,000 条流式并发请求。

| 角色 | 数量 | 单台配置 | 磁盘 | 说明 |
|---|---:|---|---|---|
| 负载均衡 LB | 2 | 4 vCPU / 8 GB | 50～100 GB SSD | Nginx/HAProxy + Keepalived；可复用现有硬件 LB |
| Gateway API | 3 | **8 vCPU / 16 GB** | 100 GB SSD | 每台 JVM `-Xms2g -Xmx8g`；预留扩至 6 台 |
| Gateway Worker | 1 | 4 vCPU / 8 GB | 100 GB SSD | 只执行定时任务，不接收用户流量；可准备 1 台冷备 |
| PostgreSQL | 2～3 | **8 vCPU / 32 GB** | 500 GB NVMe/台 | 1 主 1 备起步，重要生产建议 1 主 2 备 |
| Redis | 3 | 4 vCPU / 8 GB | 50～100 GB SSD/台 | Sentinel/Cluster 或托管服务 |
| 监控与日志 | 2～3 | 8 vCPU / 32 GB | 1 TB SSD/台起 | 根据日志量和保留期扩容 |
| 备份存储 | 1 套 | 可复用备份平台 | 2～4 TB 起 | 数据库备份、WAL、配置与审计归档 |

Gateway 容器基线可沿用仓库当前生产设计：

```yaml
resources:
  requests:
    cpu: "1"
    memory: 2Gi
  limits:
    cpu: "4"
    memory: 6Gi
```

如果按 VM 独占部署，建议直接为每台 Gateway VM 配置 8 vCPU / 16 GB，给操作系统、容器、直接内存和突发流量保留余量。

数据库连接池建议每个 Gateway 从 `PG_POOL_MAX=15` 起步。3 个 Gateway 加 Worker 和 30% 余量，可将数据库连接上限按 80～100 规划。

## 4. 方案 C：最小 POC 实验配置

### 4.1 最小单机 POC

| 角色 | 数量 | 配置 | 磁盘 | 说明 |
|---|---:|---|---|---|
| POC 一体机 | 1 | **8 vCPU / 16 GB** | **200 GB SSD/NVMe** | Docker Compose 同机运行 Gateway、PostgreSQL、Redis；模型服务使用外部 API 或独立 GPU 主机 |

建议用途：

- 10～50 个体验用户；
- 20～50 条流式并发连接；
- 验证登录、API Key、模型路由、流式输出、限流、计费和管理后台；
- 不验证高可用、数据库切换和大规模性能。

最低可降低到 4 vCPU / 8 GB / 100 GB SSD，但构建镜像、运行数据库和进行并发测试时余量很小，不建议用于正式演示。

### 4.2 较稳妥的双机 POC

| VM | 配置 | 部署内容 |
|---|---|---|
| POC-APP | 4 vCPU / 8 GB / 100 GB SSD | Nginx + Gateway |
| POC-DATA | 4 vCPU / 8 GB / 200 GB SSD | PostgreSQL + Redis |

双机 POC 可用于 50～200 个体验用户、50～100 条流式并发连接，但仍不具备真正高可用能力。

## 5. 三套方案对比

| 项目 | 3 万严格并发 | 均衡生产 | 最小 POC |
|---|---:|---:|---:|
| 在线/注册口径 | 30,000 条活动请求 | 30,000 注册，1,000～3,000 在线 | 10～50 人体验 |
| 流式并发目标 | 30,000 | 300～1,000 | 20～50 |
| Gateway 数量 | 8 起步，可扩 12 | 3 起步，可扩 6 | 1 |
| Gateway 单机 | 16C / 32G | 8C / 16G | 与数据库合用 8C / 16G |
| PostgreSQL | 3 × 16C / 64G | 2～3 × 8C / 32G | 同机容器 |
| Redis | 3 × 8C / 32G | 3 × 4C / 8G | 同机容器 |
| 高可用 | 完整 HA | 基础 HA | 无 |
| 生产可用 | 是，压测通过后 | 是，适合常规全校起步 | 否 |

## 6. 上线前必须确认

1. “3 万同时使用”究竟是 3 万在线会话，还是 3 万条同时生成中的模型请求；
2. Chat、Embedding、Rerank、图片、音频、视频的业务比例；
3. 平均输入 token、平均输出 token、平均生成时长和首 token 延迟；
4. 模型部署在校内 GPU 还是外部云 API；
5. 日志是否记录请求正文，以及日志保留天数；
6. SLA、RTO、RPO、单用户并发限制和院系配额；
7. 是否已有可复用的 LB、PostgreSQL、Redis、监控、日志和备份平台。

## 7. 推荐结论

- 如果“3 万人同时使用”是**3 万在线、约 10% 正在请求模型**，优先采用均衡生产方案，并把 Gateway 扩为 4～6 台后压测。
- 如果确实要求**30,000 条模型流式请求同时进行**，采用严格方案：8 台 16C/32G Gateway 起步、预留到 12 台，并以 45,000 并发作为验收压力。
- POC 只需 1 台 8C/16G/200G VM；POC 通过后不要原地升级为生产，应按生产拓扑重新部署 PostgreSQL、Redis、Gateway 和负载均衡。
