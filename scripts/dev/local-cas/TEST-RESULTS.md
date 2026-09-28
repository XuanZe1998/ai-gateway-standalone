# 本地模拟验收记录

测试日期：2026-09-21。环境为独立本机 HTTPS 门户/CAS、Java 17 网关和隔离 PostgreSQL。

## 已验证

- 最新前端通过 `vue-tsc` 和 Vite 构建，进入后端 JAR。
- 33 个相关 Java 回归测试通过，涵盖 CAS XML/协议、身份映射、认证过滤器、教师控制器及 CSRF。
- `verify.mjs` 的 12 项检查全部通过，验证真实 HTTPS + Cookie + ticket + state + 数据库身份映射链路，以及 SPA 深层路径和构建资源可访问。结果写入隔离目录的 `verification.json`。
- 老师获得 `TEACHER/USER`，无管理员角色；教师设备列表返回 200，管理员 API 返回 403。
- 按实际 Axios 行为发送 CSRF Cookie/Header 能完成注销；缺失 Header 被拒绝。应用和模拟 CAS 会话注销后不能静默恢复。
- 无效票据、重复校验、service 不一致、错误 state、未登记回调均被拒绝。
- 原项目 `.env` 未修改，现有 PostgreSQL 容器和数据没有被用于模拟。
- 停止/重新启动及 `reset.ps1` 实测通过；端口占用时启动立即拒绝，现有模拟进程未被终止或替换。

## 本次联调修复

1. CAS 客户端不再对已编码的 service 二次编码。
2. 认证过滤器从 Reactor SecurityContext 读取已恢复的 Session，并避免 Mono<Void> 正常完成后再次走缺少凭据分支。
3. 健康检查在自定义过滤器中精确放行，与 Spring Security 的规则一致；没有放开其他 Actuator 路径。
4. 教师控制器显式从 CampusAuthentication 取得领域身份，避免 WebFlux 的 Principal 参数绑定类型不匹配。
5. CSRF 校验兼容 SPA 发送的原始 Cookie Token，同时保留响应中的掩码 Token 和对错误/缺失 Token 的拒绝。
6. 修复模拟门户表单的 `Invalid origin`：页面原来的 `Referrer-Policy: no-referrer` 会让原生表单 POST 使用 `Origin: null`。仅 HTML 门户/登录页改用 `same-origin`，CAS 重定向保留 `no-referrer`；仍拒绝跨站、null 和缺失 Origin。新增响应头和来源校验回归，只重启模拟门户后 12 项 HTTP 检查通过。浏览器需重新打开门户首页再点击，不要重新提交旧错误页的表单。

## 未完成的浏览器验收

浏览器自动化连接失败；备用 Windows 页面读取被工具终止，原因是无法可靠确定当前浏览器 URL。没有绕过此限制。
本次 Origin 修复后再次尝试浏览器连接，仍返回 `nodeRepl.fetch request failed`，因此不将 HTTP 回归结果视为浏览器实测通过。

因此本记录 **不声称已完成浏览器点击、教师页面视觉检查、刷新和前端路由拦截的实测**。请按 README 的步骤在浏览器完成这部分验收。HTTP 接口、权限校验和注销链路已独立验证。

本地模拟结果不能代替真实学校 CAS 联调或生产验收。
