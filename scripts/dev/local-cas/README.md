# 本机校园门户 / CAS 免密登录演示

这是隔离的本地模拟，不连接学校真实门户或 CAS，不调用模型。

## 启动与操作

需要 Windows PowerShell 7、Docker Desktop（Linux 容器）、JDK 17、Node/npm、OpenSSL。
设置 `JAVA_HOME` 为 JDK 17；启动脚本也能识别本机既有的 `D:/Programs/Environment/JDK/jdk17`。
在项目根目录执行：

```powershell
./scripts/dev/local-cas/start.ps1
```

首次启动构建最新前端和后端，生成随机测试密钥和短期 localhost 证书，向 **当前 Windows 用户** 的受信根证书库导入专用测试 CA。不会安装到计算机级证书库，也不会禁用浏览器/JVM 的证书校验。

1. 打开 **https://localhost:39443**。
2. 点击「进入已登录老师场景」，门户显示「张老师（模拟） · 已登录门户」。
3. 点击「进入 AI 算力平台」。浏览器经过 CAS 和应用回调，自动进入 **https://localhost:39444/admin/teacher**，没有第二次密码输入。
4. 查看老师姓名、教师门户和设备列表；通过地址栏尝试 `/admin/dashboard/main`，应返回允许访问的页面。
5. 在项目中选择退出登录，确认退出后不能免密恢复旧身份。再次演示从门户重新建立模拟场景。

测试账号：`mock_teacher01`；平台账号：`MOCK_T1001`；身份编码：`MOCK_TEACHER`。
老师只有 `TEACHER` / `USER` 权限，没有 `ADMIN`。数据库预置个人实名状态 `(user_type=2, verify_status=2)`，仅用于此模拟。

本机端口：39443（模拟门户/CAS）、39444（应用 HTTPS）、39445（隔离 PostgreSQL）；均仅绑定 IPv4 回环地址。端口被占用时启动报错，不杀死占用端口的进程。

## 验证、停止、重置

```powershell
node ./scripts/dev/local-cas/verify.mjs
./scripts/dev/local-cas/stop.ps1
./scripts/dev/local-cas/start.ps1 -NoBuild
./scripts/dev/local-cas/reset.ps1
```

`verify.mjs` 使用独立 Cookie 会话，测试完整登录、账号/角色、教师与管理员接口权限、CSRF 注销、票据重复使用、service 绑定、无效票据、state 篡改。不改变浏览器的登录状态，不输出票据或密钥。

`reset.ps1` 重新启动模拟 CAS 和应用，使两侧内存会话及票据失效，保留隔离数据库。
`-NoBuild` 仅复用本演示之前构建的 JAR；源码变化后使用不带此参数的启动命令。

## 隔离与清理

- Docker 项目名固定为 `ai-gateway-local-cas-demo`；不复用现有数据库/数据卷，不读取或修改项目 `.env`。
- `.local-cas-demo/` 保存生成的证书、密钥、独立信任库、进程信息、日志、JAR 和 `verification.json`。目录不提交版本控制，限制为当前用户及 SYSTEM 访问。
- 教师设备模块仅为页面数据读取启用，测试不执行绑定、发放短令牌或模型推理。示例中的无需客户端证书配置不会导入生产配置。
- 常规停止保留数据库和测试 CA。下列操作显式删除 **仅本模拟环境的数据卷** 并移除记录指纹对应的当前用户测试 CA：

```powershell
./scripts/dev/local-cas/cleanup.ps1 -RemoveData -RemoveCertificate
```

删除数据卷不可恢复；重新启动会重新创建模拟数据，并重新导入同一测试 CA。证书到期后，应停止演示、移除 CA，然后只删除 `.local-cas-demo/` 内生成的证书和信任库再启动以重新生成；不要删除仍需保留的数据卷凭据。

真实校方联调仍需要实际回调登记、真实身份编码、用户数据和校方测试账号。本地成功不代表学校端已接通。
