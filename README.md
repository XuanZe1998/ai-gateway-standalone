# AI Gateway Standalone

<!-- 版本信息 -->
> **文档版本**: 1.0.14  
> **最后更新**: 2026-09-29  
> **Git 提交**: 57a80611  
> **作者**: GitHub Action
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

独立实例使用自己的 PostgreSQL、自己的 JWT 账号、自己的 API Key 和自己的模型实例配置。平台相关实体代码仍保留以兼容原有计费数据结构，但 standalone 模式不会主动同步平台数据。

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

默认没有连接任何模型服务，以避免误连原南通环境。可在管理后台“实例管理”中添加，或编辑：

```text
config-external/router/services.yml
```

所有服务类型的完整示例位于：

```text
config-external/router/services.example.yml
```

将需要的实例复制到 `services.yml`，设置 `UPSTREAM_BASE_URL`、`UPSTREAM_API_KEY` 和模型名，然后重启：

```powershell
docker compose restart gateway
```

容器访问宿主机模型服务时可使用 `http://host.docker.internal:<端口>`。

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

CAS 首次登录自动创建独立的 `cas:` 平台用户。临时开放模型调用时，可设置 `CAMPUS_UNRESTRICTED_ACCESS=true`（不扣本地余额，但上游仍产生实际费用）；管理员默认由 `CAMPUS_ADMIN_ACCOUNTS` 控制；临时设置 `CAMPUS_DEFAULT_ADMIN=true` 可让所有 CAS 用户获得管理员权限。
