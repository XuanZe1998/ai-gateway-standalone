# AI Gateway Standalone

<!-- 版本信息 -->
> **文档版本**: 1.0.0  
> **最后更新**: 2026-09-28  
> **Git 提交**: 05931b92  
> **作者**: XuanZe1998
<!-- /版本信息 -->



从南通算力平台项目中独立抽取的 AI 网关。该目录可以单独构建、启动和持久化运行，不依赖商城后端、平台管理前端或南通平台数据库。

## 已保留的网关能力

- OpenAI 风格 Chat Completions（含流式响应）
- Embedding、Rerank
- TTS 语音合成、STT 语音识别
- 图片生成、图片编辑
- 视频生成异步任务及状态查询
- 多渠道、多模型实例注册和动态配置
- 轮询、随机、最少连接、IP Hash 等负载均衡
- 限流、熔断、健康检查和降级
- API Key、JWT、RBAC、安全审计与脱敏
- 调用日志、指标、Prometheus、链路追踪
- 用量记录、定价和计费基础能力
- 内置 Vue 3 管理后台与 Playground
- 配置版本管理、JPA/文件状态持久化

## 已解除的平台依赖

`standalone` profile 设置 `jairouter.platform.enabled=false`，不会执行以下南通平台同步：

- `ai_model` 模型同步
- `ai_channel` 渠道同步
- `ai_api_key` 平台密钥兜底认证
- 平台模型定价同步

独立实例使用自己的 PostgreSQL、自己的 JWT 账号、自己的 API Key 和自己的模型实例配置。平台相关实体代码仍保留以兼容原有计费数据结构，但 standalone 模式不会主动同步平台数据。**CAS 登录是例外**：它仍按现有业务规则在本实例数据库的 `sldd_system_users` 中查找账号并校验实名状态，因此接入前必须准备获授权的用户数据或另行设计账号映射方案。

## 一键启动

要求：Docker Desktop / Docker Engine，支持 `docker compose`。

```powershell
Copy-Item .env.example .env
# 建议先修改 .env 中的密码与密钥
.\start.ps1
```

也可以直接运行：

```powershell
docker compose up -d --build
```

## 公网 HTTPS 入口（单机 Compose）

`docker-compose.public.yml` 增加 Caddy 反向代理，由它在 80/443 端口接收外部请求并自动申请 HTTPS 证书。网关的 `38008` 端口及 PostgreSQL 端口只绑定本机，不能直接对外开放。`deploy/caddy/Caddyfile` 同时拦截公网对 Actuator、Swagger 和内部接口的访问。

启用前需要一个**固定且解析到入口公网 IP 的域名**，以及公网到运行本项目机器的 TCP 80、TCP 443（可选 UDP 443）连通性。如果在路由器后面，需映射端口并放行系统防火墙；如果没有可入站的公网 IP，需使用提供固定 HTTPS 域名的隧道或在服务器上部署。仅修改 Compose 文件不能打通运营商网络、路由器和防火墙。

在 `.env` 中设置下面两个值，其中域名必须替换为实际域名。公网编排会自动把 CAS 回调基址设为该域名的 HTTPS 地址，并启用 Secure 会话 Cookie：

```dotenv
GATEWAY_BIND_IP=127.0.0.1
PUBLIC_DOMAIN=your-gateway.example.edu
```

对外开放前还必须更换 `.env` 中的 `PG_PASSWORD`、`JWT_SECRET`、`INITIAL_ADMIN_PASSWORD`、`GATEWAY_API_KEY` 等开发凭据。已有 PostgreSQL 数据卷不会因为修改 `.env` 自动更换数据库密码，必须先在数据库中同步修改密码。若启用 CAS，向学校登记 `https://<实际域名>/api/auth/cas/callback`（回调会带动态 `state` 查询参数）。CAS 用户统一获得普通 `USER` 权限；管理员可使用 `CAMPUS_ADMIN_ACCOUNTS` 白名单；临时设置 `CAMPUS_DEFAULT_ADMIN=true` 则所有 CAS 用户获得管理员权限。不要将 `localhost` 或示例域名提交给学校。

配置完成后启动：

```powershell
docker compose -f docker-compose.yml -f docker-compose.public.yml up -d --build
```

公网登录入口为 `https://<实际域名>/api/auth/cas/login`，管理页面为 `https://<实际域名>/admin/`。先从手机移动网络验证 HTTPS 和页面可访问，再用 CAS 账号完整走一遍登录回调。没有固定 HTTPS 域名时，公网 CAS 登录尚无法完成配置。

### `gordanshop.com` 的 Cloudflare Tunnel 方式

该域名当前使用 Cloudflare 的权威 DNS，适合通过固定 Tunnel 从本机向外建立连接。Tunnel 编排从 `.env` 的 `CAMPUS_PUBLIC_BASE_URL` 读取 CAS 回调基址；当前部署为 `https://gordanshop.com`。内部代理拦截公网对 Actuator、Swagger 和内部接口的请求。此方式无需公网 IP，也无需开放本机或路由器的 80/443 端口。

1. 在管理 `gordanshop.com` 的 **Cloudflare 账户**中创建一个 remotely-managed Tunnel（例如 `ai-gateway-standalone`）。新网的域名管理页仅是注册商页面；由于权威 NS 指向 Cloudflare，网站的 DNS 路由要在 Cloudflare 设置。
2. 在 Tunnel 的 Published application 路由中，将 hostname 设置为主域名 `gordanshop.com`（subdomain 留空），Service URL 设置为 `http://tunnel-proxy:8081`。Cloudflare 会创建指向该 Tunnel 的 DNS 记录。不要把服务 URL 写成 `localhost`，因为连接器运行在 Docker 容器内。
3. 从该 Tunnel 的安装命令中取出令牌，只把令牌原文保存在项目根目录的 `.cloudflare-tunnel-token` 文件。此文件已被 `.gitignore` 排除；不要将令牌发到聊天或提交到仓库。
4. 在启动前更换 `.env` 中的开发密码和密钥。已有 PostgreSQL 数据卷的数据库密码也要在数据库内同步修改。CAS 登录无需配置 `typeCode` 映射。
5. 可先连接 Tunnel，让 Cloudflare 向导检测连接器；这只启动内部代理和连接器，不重建网关。随后运行完整启动：

```powershell
.\start-tunnel.ps1 -ConnectorOnly
# 凭据及域名配置完成后：
.\start-tunnel.ps1
docker compose -f docker-compose.yml -f docker-compose.tunnel.yml ps
```

然后从非本机网络验证 `https://gordanshop.com/admin/`，并向学校登记 `https://gordanshop.com/api/auth/cas/callback`。浏览器应从 `https://gordanshop.com/api/auth/cas/login` 发起登录，CAS 回调会带动态 `state` 参数。学校门户应用入口填写 `https://gordanshop.com/api/auth/cas/login?target=%2Fadmin%2Fauth%2Fcallback`。如果学校的“匹配地址”使用正则表达式，填写 `^https://gordanshop\.com/api/auth/cas/callback\?state=[A-Za-z0-9_-]+$`，使动态 `state` 能匹配且不接受其他域名。若学校的“单点注销地址”指接收 CAS 后台注销通知的接口，填写 `https://gordanshop.com/api/auth/cas/slo`；该接口接收带 `logoutRequest` 的表单 POST，按 `SessionIndex` 注销对应业务会话。若校方字段含义不同，须以校方平台说明为准。

CAS 首次登录会按 CAS 服务地址与 `subject` 创建独立的 `cas:` 平台用户，并保存身份绑定；不会因同名 `account` 自动关联到已有平台用户。新用户的实名认证状态为未认证，不会伪造实名结果。默认只有 `CAMPUS_ADMIN_ACCOUNTS` 白名单获得管理员权限；当前临时设置 `CAMPUS_DEFAULT_ADMIN=true` 会让所有 CAS 用户获得 `ADMIN`，关闭后恢复白名单。

**临时开放模型调用**：在 `.env` 设置 `CAMPUS_UNRESTRICTED_ACCESS=true` 并重建网关容器。该开关仅作用于 CAS 会话：允许未实名、无余额用户使用包括付费模型在内的已配置模型；账单仍记录模型用量和名义费用，但不从平台账户扣余额。上游模型供应商仍会产生实际费用，请监测用量；关闭开关并重启网关后恢复实名认证与余额校验。模型放行开关本身不授予管理员权限；管理员临时放行由独立的 `CAMPUS_DEFAULT_ADMIN` 开关控制，不影响 API Key、JWT 等其他认证方式。

## 分布式集群启动

项目提供三副本 Gateway、独立 Worker、共享 PostgreSQL、Redis 和 Nginx 的本地集群验证编排：

```bash
cp .env.cluster.example .env.cluster
# 必须修改 .env.cluster 中的所有密码和密钥
docker compose --env-file .env.cluster -f docker-compose.cluster.yml up -d --build
```

集群入口仍为 `http://localhost:38008`。该编排用于功能验证和压测，不替代生产 PostgreSQL/Redis 高可用集群。

生产 Kubernetes 基线位于 `deploy/kubernetes/`，详细架构和上线要求见：

```text
docs/zh/deployment/campus-distributed-deployment.md
```

集群模式具备：

- PostgreSQL 中心化运行时配置和跨节点自动刷新；
- Redis Lua 原子分布式限流；
- API 与 Worker 角色分离，防止定时任务重复执行；
- readiness/liveness、优雅停机和滚动发布配置；
- 配置并发写入乐观锁保护。

停止：

```powershell
.\stop.ps1
```

停止并清空 PostgreSQL、配置和状态数据：

```powershell
.\stop.ps1 -RemoveData
```

## 默认地址

- 管理后台：`http://localhost:38008/admin/`
- Swagger：`http://localhost:38008/swagger-ui.html`
- OpenAPI JSON：`http://localhost:38008/v3/api-docs`
- 健康检查：`http://localhost:38008/actuator/health`
- Prometheus：`http://localhost:38008/actuator/prometheus`

首次本地启动（未修改 `.env.example`）的凭据：

- 管理员：`admin` / `change-this-admin-password`
- API Key：`sk-change-this-api-key`

> 以上仅用于本机启动。部署到可访问网络前必须修改 `.env`。

## 配置上游模型

上游实例有两种配置来源：

1. **管理后台动态配置（推荐）**：在“服务管理/实例管理”中添加。数据持久化到 PostgreSQL 的 `service_config`、`service_instance` 表，重启后仍会加载。
2. **静态启动配置**：编辑 `config-external/router/services.yml`，适合首次部署或配置即代码场景。

> `services.yml` 为空只表示没有静态启动实例，**不能据此判断运行时没有模型**。管理后台保存的实例仍可能存在于 PostgreSQL。可通过 `GET /v1/models` 或管理后台“实例管理”确认运行时模型列表。

所有服务类型的静态配置示例位于：

```text
config-external/router/services.example.yml
```

修改静态 YAML 后重启网关：

```powershell
docker compose restart gateway
```

不要把已经存在于 PostgreSQL 的实例再次批量写入 YAML，以免产生重复配置。容器访问宿主机模型服务时可使用 `http://host.docker.internal:<端口>`。

## API 示例

Chat：

```bash
curl http://localhost:38008/api/v1/chat/completions \
  -H "Authorization: Bearer sk-change-this-api-key" \
  -H "Content-Type: application/json" \
  -d '{"model":"your-model","messages":[{"role":"user","content":"你好"}]}'
```

其他入口：

- `POST /api/v1/embeddings`
- `POST /api/v1/rerank`
- `POST /api/v1/audio/speech`
- `POST /api/v1/audio/transcriptions`
- `POST /api/v1/images/generations`
- `POST /api/v1/images/edits`
- `POST /api/v1/videos/generations`
- `GET /api/v1/videos/generations/task/{taskId}`

API Key 同时支持：

```http
Authorization: Bearer <api-key>
X-API-Key: <api-key>
```

## 本地源码构建

Java 17：

```powershell
$env:JAVA_HOME='D:\Programs\Environment\JDK\jdk17'
& .\mvnw.cmd package '-DskipTests' '-Dcheckstyle.skip=true' '-Dspotbugs.skip=true' '-Djacoco.skip=true'
```

运行已构建 JAR（需自行准备 PostgreSQL）：

```powershell
$env:SPRING_PROFILES_ACTIVE='standalone'
$env:PG_HOST='localhost'
$env:PG_PORT='5432'
$env:PG_DB='ai_gateway'
$env:PG_USER='ai_gateway'
$env:PG_PASSWORD='ai_gateway'
java -jar target\ai-gateway-standalone-2.6.11.jar
```

## 目录说明

- `src/main/java`：网关后端源码
- `src/main/resources/static/admin`：已构建管理后台
- `src/main/resources/application-standalone.yml`：独立运行配置
- `config-external/router/services.yml`：容器外部模型实例配置
- `Dockerfile.standalone`：独立镜像构建
- `docker-compose.yml`：网关 + PostgreSQL
- `start.ps1` / `stop.ps1`：Windows 一键启停
- `start.sh` / `stop.sh`：Linux/macOS 一键启停

上游项目原 README 已保存到 `docs/README-UPSTREAM.md`。

