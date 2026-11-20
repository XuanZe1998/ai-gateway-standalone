# 本地模型图标库

本目录包含 29 个品牌图标和 9 个通用服务图标，共 38 个 SVG。

- 品牌图标来自 [LobeHub Lobe Icons](https://github.com/lobehub/lobe-icons)，固定下载 `@lobehub/icons-static-svg@1.95.1`，保留 `LICENSE-LobeHub`。
- 通用图标来自 [Lucide](https://github.com/lucide-icons/lucide/tree/0.468.0/icons)，固定版本 `0.468.0`，保留 `LICENSE-Lucide`。
- `sources.json` 记录每个图标的下载地址和本地文件 SHA-256。为了让外部 SVG 图片具有稳定颜色，下载脚本把 `currentColor` 替换为 `#18354d`，换行统一为 LF；其他图形路径保持原样。Git 属性保持 SVG 的 LF 换行，以便各平台检出的文件哈希一致。
- `catalog.json` 是前后端共用的配置：图标 ID、名称、文件、模型名称规则、品牌名称规则和服务类型默认图标。

模型广场卡片、详情及后台内容管理共用 `ModelIcon.vue`。Vite 将 SVG 打包为本地资源或内嵌 data URL，显示时不请求第三方站点。

自动匹配顺序：实际模型 ID（含命名空间后的 ID）→ 平台默认展示名 → 厂商品牌 → 服务类型默认图标。`deepseek-ai/DeepSeek-R1-Distill-Qwen-32B` 按 DeepSeek 显示；`text-embedding-v3/v4`、`gte` 使用通义图标，`text-embedding-3-*` 使用 OpenAI 图标。通义万相使用阿里巴巴品牌图标，目录标签已明确标注。

按用户要求，`catalog.json.deferredModelPattern` 暂缓 krx_lm、ntk_core、orb/ orb2、vlt_mm 系列的自动品牌匹配，使用通用服务图标；管理员仍可对单个模型手动选择。

管理员在「模型与计费 → 模型内容 → 编辑内容 → 模型图标」中选择图标，保存为 `model_square_content.icon_id`。留空恢复自动匹配；「恢复默认」清除整条内容覆盖。后端只接受目录中的图标 ID，不接受任意文件路径或外部图片 URL。

重新下载：在仓库根目录运行 `python scripts/assets/download-model-icons.py`。新增品牌时，先在 `catalog.json` 增加图标和固定版本来源，再运行脚本并执行前后端验证。
