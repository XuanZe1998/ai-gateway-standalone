# 模型广场图标配置（2026-10-14）

原图标在 `frontend/src/views/ModelSquare.vue` 按服务类型写死。本次改为共用本地图标库，并给内容管理增加每个模型的图标选择、预览和恢复自动匹配。

## 模型配置

依据仓库 `model-list-classified-20261011.json` 的 103 个模型 ID：72 个匹配品牌图标，31 个内部代号按用户要求暂不配置品牌，继续使用通用对话图标。实际运行目录新增模型时，使用同一套规则自动匹配。

| 图标 | 模型数量 |
|---|---:|
| Qwen / 通义千问 | 25 |
| DeepSeek | 9 |
| Doubao / 豆包 | 9 |
| GLM / 智谱 | 7 |
| 通义万相 / 阿里巴巴 | 7 |
| Kimi | 5 |
| MiniMax | 4 |
| HappyHorse | 4 |
| Agnes AI | 2 |
| krx_lm、ntk_core、orb/ orb2、vlt_mm（暂缓） | 31 |

逐模型结果见 [model-square-icon-assignments-20261014.json](model-square-icon-assignments-20261014.json)。这是保存清单的配置报告，不是本次从生产重新拉取的模型列表。

## 图标来源与设置位置

- 本地图标及统一配置：`src/main/resources/static/model-icons/`，38 个 SVG，包含下载来源、文件哈希及许可证。
- 品牌来源：[LobeHub Lobe Icons](https://github.com/lobehub/lobe-icons)，固定 `@lobehub/icons-static-svg@1.95.1`。
- 通用图标来源：[Lucide 0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0/icons)。
- Agnes 系列名称核验：[Agnes AI 官方模型目录](https://github.com/AgnesAI-Labs/AgnesAI-Models/blob/main/MODEL_CATALOG.md)。
- 管理入口：`/admin/system/model-square`，模型内容 → 编辑内容 → 模型图标。未手选图标时自动匹配，无须逐条创建内容覆盖。
- API 的 `Card` 和 `Content` 新增 `iconId` 字段；旧请求未传字段时使用自动匹配。自定义图标写入 `model_square_content.icon_id`。

## 发布记录

已于 2026-10-14 发布到当前运行平台；最终镜像标签为 `ai-gateway-standalone:model-icons-20261014-r2`。现有模型内容、收费与运行配置保留。

升级现有数据库前执行 `src/main/resources/db/migration/postgresql/V24__model_square_icons.sql`。迁移仅增加可空 `VARCHAR(64)` 字段，不覆盖原展示名、介绍和标签。若部署环境关闭 Flyway，应按现有发布流程显式执行迁移，再部署新的前后端构建。

## 验证

- 前端模型广场测试 17 项通过，类型检查及生产构建成功。
- 后端图标、模型广场服务、控制器与访问控制测试 18 项通过。
- 在独立 PostgreSQL 16.15 容器执行 V13 和 V24，真实 JPA 验证图标保存、读取、更新、清空及删除；持久化测试 1 项通过。
- 本地浏览器使用清楚标注的模拟数据验证品牌图标、本地加载、管理员选择框及切换后的即时预览。没有使用生产凭据或创建收费模型调用。
- 当前构建的 Checkstyle、SpotBugs、JaCoCo 按 fast profile 跳过，未称其通过。


## GitHub 提交范围与线上验收

- 提交只包含模型图标相关文件与改动，未提交工作区其他账户、计费、布局、配置修改或历史文件删除。
- 将待提交的 Git 树单独导出，前端测试 16 项、后端图标 / 广场服务 / 控制器 / 访问控制测试 16 项均通过；独立前端类型检查和生产构建成功。
- 图标的换行统一为 LF，新增 Git 属性防止 Windows 检出时改变已记录的 SVG 哈希。
- V24 在生产备份的隔离恢复副本中完成演练，正式迁移事务执行成功；字段类型为 `VARCHAR(64)`。
- 公网管理员登录、模型列表、内容列表和详情读取通过；103 个模型全部返回合法本地图标 ID，其中 72 个品牌图标、31 个通用图标，与保存的配置报告一致。
- 公网模型图标、广场、内容管理脚本哈希与本地构建一致；匿名接口访问仍被拒绝。
- 网关健康 UP；数据库、Caddy 和 Cloudflare 容器的 ID / 启动时间未变化。
- 私有发布备份、回滚镜像和运行快照保存在 `.local-ops/releases/model-icons-release-20261014-r2/`，不提交 GitHub。回滚只恢复此次网关镜像并保留新增可空字段。
