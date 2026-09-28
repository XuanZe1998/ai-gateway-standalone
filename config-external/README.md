# 外部配置

此目录由 `docker-compose.yml` 只读挂载到容器 `/app/config`。

- `application.yml`：日志、管理端点等部署覆盖项
- `auth/jwt.yml`：JWT 覆盖项
- `auth/teacher-access.yml`：教师访问基础配置；真实 OIDC/学校 CA 就绪前保持关闭
- `application-teacher.example.yml`：生产 OIDC + mTLS 配置模板
- `tls-dev/`：本地 mTLS 联调证书（已忽略，禁止用于生产）
- `router/services.yml`：静态启动模型实例；管理后台动态实例另存于 PostgreSQL 的 `service_config`/`service_instance` 表
- `router/services.example.yml`：Chat、Embedding、Rerank、TTS、STT、图片、视频完整示例

推荐通过根目录 `.env` 保存密码、JWT 密钥和上游访问令牌，不要把真实密钥提交到源码。
修改 YAML 后执行：

```powershell
docker compose restart gateway
```


