# 非聊天接口现状分析与计费补齐计划

> 分析日期：2025-06-05
> 基于 dev 分支当前代码，7 个端点全链路分析
> Review 日期：2025-06-05（代码逐行验证）

---

## 一、接口全景：AI 服务能力与模型映射

### 1.1 七大接口 × AI 能力一览

| # | 端点 | ServiceType | 上游路径 | AI 能力 | 输入→输出 | 请求格式 | 响应格式 |
|---|------|-------------|----------|---------|-----------|----------|----------|
| 1 | `/api/v1/chat/completions` | `chat` | `/v1/chat/completions` | **对话补全** | 文本→文本（唯一支持 SSE 流式） | JSON | JSON / SSE |
| 2 | `/api/v1/embeddings` | `embedding` | `/v1/embeddings` | **文本向量化** | 文本→向量（语义搜索、聚类、相似度） | JSON | JSON |
| 3 | `/api/v1/rerank` | `rerank` | `/v1/rerank` | **重排序** | query + 文档列表→按相关性排序（RAG 检索增强） | JSON | JSON |
| 4 | `/api/v1/audio/speech` | `tts` | `/v1/audio/speech` | **语音合成（TTS）** | 文本→音频 | JSON | `byte[]`（音频二进制） |
| 5 | `/api/v1/audio/transcriptions` | `stt` | `/v1/audio/transcriptions` | **语音识别（STT）** | 音频→文本 | Multipart | JSON |
| 6 | `/api/v1/images/generations` | `imgGen` | `/v1/images/generations` | **图片生成** | 文本描述→图片 | JSON | JSON（含 URL/base64） |
| 7 | `/api/v1/images/edits` | `imgEdit` | `/v1/images/edits` | **图片编辑** | 图片 + 文本指令→编辑后图片 | JSON | JSON（含 URL/base64） |

> `vidGen`（视频生成）在 ServiceType 枚举中存在，但无 Controller 端点、无适配器方法、无 DTO，属于占位状态。

### 1.2 各接口请求参数与典型场景

#### ① chat — 对话补全（LLM 核心）

- **参数**：`model`、`messages`（对话历史，含 role/content）、`stream`、`temperature`、`maxTokens`、`topP`、`topK`、`frequencyPenalty`、`presencePenalty`、`stop` 等
- **场景**：问答、对话、代码生成、摘要、翻译、Function Calling、思维链推理
- **唯一支持 SSE 流式**的接口，其余接口均为同步请求-响应

#### ② embedding — 文本向量化

- **参数**：`model`、`input`（字符串或字符串数组）、`encodingFormat`（如 "float"）、`dimensions`（向量维度）
- **场景**：语义搜索（RAG 向量库构建）、文本聚类、相似度计算
- **返回**：每条输入对应一个 float 向量数组 + `usage.prompt_tokens`

#### ③ rerank — 重排序

- **参数**：`model`、`query`（查询文本）、`documents`（待排序文档列表）、`topN`（返回前 N 个）、`returnDocuments`
- **场景**：RAG 管线中对检索结果二次排序，提升最终召回质量
- **返回**：文档按相关性得分降序排列 + `usage.total_tokens`

#### ④ tts — 语音合成

- **参数**：`model`、`input`（待合成文本）、`voice`（音色预设，如 "alloy"/"echo"）、`responseFormat`（mp3/opus）、`speed`
- **场景**：文字转语音播报、有声读物生成、客服语音合成
- **返回**：音频二进制流（Content-Type: audio/mpeg 等），无 JSON usage

#### ⑤ stt — 语音识别

- **参数**：`model`、`file`（音频文件，Multipart 上传）、`language`（语言提示）、`prompt`（引导文本）、`responseFormat`、`temperature`
- **场景**：语音转写、会议记录、字幕生成
- **返回**：`text`（识别文本）、`language`、`duration`、`segments`，无 usage 字段

#### ⑥ imgGen — 图片生成

- **参数**：`prompt`（图片描述）、`model`、`n`（生成数量）、`size`（尺寸）、`quality`、`style`、`response_format`（url/base64）
- **场景**：文生图（text-to-image），海报生成、概念可视化
- **返回**：图片 URL 或 base64 数据 + `usage.total_tokens`/`input_tokens`/`output_tokens`

#### ⑦ imgEdit — 图片编辑

- **参数**：`image`（源图片列表）、`prompt`（编辑指令）、`model`、`mask`（inpainting 遮罩）、`size`、`quality`、`output_format`、`n`
- **场景**：图片局部修改、风格转换、inpainting（遮罩修复）、图片合成
- **返回**：编辑后图片 URL 或 base64 + `usage.total_tokens`/`input_tokens`/`output_tokens`

### 1.3 模型→接口映射规则

**核心机制**：请求中的 `model` 字段 → `selectInstance(serviceType, modelName)` → 在对应 serviceType 的实例池中按名称匹配。

调用方只需指定 `model` 参数，网关根据**端点路径**确定 serviceType，再在对应实例池查找同名模型：

```
POST /api/v1/chat/completions   { "model": "qwen3:4b" }      → 在 chat 实例池找 qwen3:4b
POST /api/v1/embeddings         { "model": "nomic-embed-text-v1.5" } → 在 embedding 实例池找 nomic-embed-text-v1.5
POST /api/v1/rerank             { "model": "bge-reranker-v2-m3" }    → 在 rerank 实例池找 bge-reranker-v2-m3
```

**实例来源有两个**，按优先级合并：

1. **算力平台同步**：`ai_model` 表中 `status='1'`（已上架）的模型，由 `PlatformDataSyncService` 按 `modelType` 翻译到对应 serviceType 实例池
2. **YAML 兜底**：`model-services-base.yml` 中静态配置的实例，补充平台未覆盖的类型

### 1.4 各 serviceType 的可用模型

#### 算力平台实例（modelType 1-6，从 `ai_model` 表同步）

| platform modelType | ServiceType | AI 能力 | 典型模型举例 | 实例来源 |
|---|---|---|---|---|
| `1` | `chat` | 对话补全 | qwen、deepseek、llama 等对话模型 | 算力平台 `ai_model` |
| `5` | `embedding` | 文本向量化 | nomic-embed、bge 等嵌入模型 | 算力平台 `ai_model` |
| `6` | `rerank` | 重排序 | bge-reranker 等排序模型 | 算力平台 `ai_model` |
| `2` | `imgGen` | 图片生成 | stable-diffusion、dall-e 等 | 算力平台 `ai_model` |
| `4` | `tts` | 语音合成 | cosyvoice、tts-1 等 | 算力平台 `ai_model` |
| `3` | `vidGen` | 视频生成 | — | 算力平台 `ai_model`（但 channel 无 videoUrl） |

> ⚠️ **STT（语音识别）和 imgEdit（图片编辑）没有独立的 platform modelType**。平台 6 种数字编码（1-6）不包含这两种类型。

#### YAML 兜底实例（`model-services-base.yml`，指向 `172.16.30.6:9090` GPUStack）

| ServiceType | YAML 实例名 | AI 能力 | 上游路径 |
|---|---|---|---|
| `chat` | `qwen3:4b` | 对话补全 | `/v1/chat/completions` |
| `embedding` | `nomic-embed-text-v1.5`, `bge-large-zh-v1.5` | 文本向量化 | `/v1/embeddings` |
| `rerank` | `bge-reranker-v2-m3` | 重排序 | `/v1/rerank` |
| `tts` | `cosyvoice-300m` | 语音合成 | `/v1/audio/speech` |
| `stt` | `faster-whisper-tiny` | 语音识别 | `/v1/audio/transcriptions` |
| `imgGen` | `stable-diffusion-2-1` | 图片生成 | `/v1/images/generations` |
| `imgEdit` | `stable-diffusion-2-1` | 图片编辑 | `/v1/images/edits` |
| `vidGen` | —（无 YAML 配置） | 视频生成 | — |

### 1.5 调用示例速查

```bash
# 对话补全（流式）
curl -X POST http://gateway:8080/api/v1/chat/completions \
  -H "Authorization: Bearer <api-key>" \
  -d '{"model":"qwen3:4b","messages":[{"role":"user","content":"你好"}],"stream":true}'

# 文本向量化
curl -X POST http://gateway:8080/api/v1/embeddings \
  -H "Authorization: Bearer <api-key>" \
  -d '{"model":"nomic-embed-text-v1.5","input":"Hello world"}'

# 重排序（RAG 场景）
curl -X POST http://gateway:8080/api/v1/rerank \
  -H "Authorization: Bearer <api-key>" \
  -d '{"model":"bge-reranker-v2-m3","query":"什么是量子计算","documents":["文档A","文档B","文档C"],"topN":3}'

# 语音合成（返回音频二进制）
curl -X POST http://gateway:8080/api/v1/audio/speech \
  -H "Authorization: Bearer <api-key>" \
  -d '{"model":"cosyvoice-300m","input":"你好，欢迎体验语音合成","voice":"alloy"}' \
  --output speech.mp3

# 语音识别（上传音频文件）
curl -X POST http://gateway:8080/api/v1/audio/transcriptions \
  -H "Authorization: Bearer <api-key>" \
  -F "model=faster-whisper-tiny" -F "file=@audio.mp3" -F "language=zh"

# 图片生成
curl -X POST http://gateway:8080/api/v1/images/generations \
  -H "Authorization: Bearer <api-key>" \
  -d '{"model":"stable-diffusion-2-1","prompt":"一只可爱的猫咪","size":"512x512"}'

# 图片编辑
curl -X POST http://gateway:8080/api/v1/images/edits \
  -H "Authorization: Bearer <api-key>" \
  -d '{"model":"stable-diffusion-2-1","prompt":"把背景换成海滩","image":[...]}'
```

## 二、架构共性

所有 7 个端点共享同一套管道，ServiceType 仅影响 configKey 查找（决定从哪个实例池选实例）：

```
Controller → selectInstance(serviceType, modelName) → getAdapter(serviceType, instance) → adapter.xxx(...)
  → BaseAdapter.processRequest() → balanceCheck → processRequestWithRetry → recordBilling
```

- 路由选择：`ModelServiceRegistry.selectInstance()` 对所有 serviceType 一视同仁
- 适配器查找：都走 `AdapterRegistry`，默认 normal（NormalOpenAiAdapter）
- 余额校验：`BalanceCheckService.checkBalance()` 对所有类型生效
- 计费触发：`recordTokenUsage()` → `recordBilling()` 对所有类型都执行

## 三、路由层：算力平台数据替换后实例可用性

### 算力平台 modelType 映射

| platform modelType | 含义 | 内部 ServiceType | resolveChannelServiceUrl | detectPath 兜底 |
|---|---|---|---|---|
| 1 | 对话 | `chat` | `channel.chatCompletionUrl` | `/v1/chat/completions` |
| 5 | 嵌入 | `embedding` | `channel.embeddingUrl` | `/v1/embeddings` |
| 6 | 重排序 | `rerank` | `channel.rerankUrl` | `/v1/rerank` |
| 2 | 图片 | `imgGen` | `channel.imageUrl` | `/v1/images/generations` |
| 4 | 语音 | `tts` | `channel.audioUrl` | `/v1/audio/speech` |
| 3 | 视频 | `vidGen` | null（channel 无 videoUrl 字段） | `/v1/videos/generations` |
| — | STT | `stt` | **无映射** | **无兜底路径** |
| — | 图片编辑 | `imgEdit` | **无映射** | **无兜底路径** |

- STT 和 imgEdit 在算力平台中不存在对应的 modelType（平台只有 1-6）
- `detectPath()` 中 stt/imgEdit 无条目，default 兜底到 `/v1/chat/completions`（路径错误）

### YAML 兜底配置

> 详见 Section 1.4「YAML 兜底实例」表格。7 种类型均有静态配置，指向 `172.16.30.6:9090` GPUStack。

### 路由层结论

| 接口 | 算力平台实例 | YAML 兜底 | 能路由？ |
|------|---|---|---|
| chat | 有（modelType=1） | 有 | 完全可用 |
| embedding | 有（modelType=5） | 有 | 完全可用 |
| rerank | 有（modelType=6） | 有 | 完全可用 |
| tts | 有（modelType=4） | 有 | 完全可用 |
| imgGen | 有（modelType=2） | 有 | 完全可用 |
| stt | 无平台 modelType | YAML 有 | 仅 YAML 实例可用 |
| imgEdit | 无平台 modelType | YAML 有 | 仅 YAML 实例可用 |

## 四、计费层：Token 提取与扣费准确性

计费公式：`cost = inputPrice × promptTokens + outputPrice × completionTokens`

token 提取统一用 JSON 路径：`data.usage.prompt_tokens` / `data.usage.completion_tokens`

| 接口 | 上游响应 usage 格式 | prompt_tokens | completion_tokens | 计费结果 |
|------|---|---|---|---|
| chat | `{ prompt_tokens, completion_tokens, total_tokens }` | 正确 | 正确 | **正确扣费** |
| embedding | `{ prompt_tokens, total_tokens }` | 正确 | =0（合理） | **按 input 计费，正确** |
| rerank | `{ total_tokens }` | =0 读不到 | =0 读不到 | **cost 恒为 0** |
| tts | `byte[]` 二进制，无 JSON | =0 | =0 | **cost 恒为 0** |
| stt | 无 usage 字段 | =0 | =0 | **cost 恒为 0** |
| imgGen | `{ input_tokens, output_tokens }` | =0（字段名不匹配） | =0（字段名不匹配） | **cost 恒为 0** |
| imgEdit | `{ input_tokens, output_tokens }` | =0（字段名不匹配） | =0（字段名不匹配） | **cost 恒为 0** |

### 各类型 0 元计费原因

1. **rerank** — 上游返回 `usage.total_tokens`，代码只读 `prompt_tokens`/`completion_tokens`，都读不到
2. **tts** — 响应是 `byte[]`（音频二进制），`objectMapper.valueToTree(byte[])` 解析不出 JSON
3. **stt** — 上游响应（Whisper 等）根本没有 `usage` 字段
4. **imgGen / imgEdit** — OpenAI 图片接口返回 `input_tokens`/`output_tokens`，代码读的是 `prompt_tokens`/`completion_tokens`，字段名对不上

> 注意：cost=0 不阻塞请求，billing_record 仍然保存，BalanceDeductionService 跳过金额 ≤ 0 的扣减。

## 五、总评

| 接口 | 路由 | 计费 | 总评 |
|------|------|------|------|
| chat | 平台+YAML | 正确 | 🟢 完全可用 |
| embedding | 平台+YAML | 正确 | 🟢 完全可用 |
| rerank | 平台+YAML | 免费（cost=0） | 🟡 请求可用，不计费 |
| tts | 平台+YAML | 免费（cost=0） | 🟡 请求可用，不计费 |
| imgGen | 平台+YAML | 免费（cost=0） | 🟡 请求可用，不计费 |
| stt | 仅 YAML | 免费（cost=0） | 🟠 请求可用但依赖本地实例，不计费 |
| imgEdit | 仅 YAML | 免费（cost=0） | 🟠 请求可用但依赖本地实例，不计费 |

## 六、计费补齐待办（后续排期）

### P1：字段名对齐（改动最小，收益最大）

- **文件**：`BaseAdapter.recordTokenUsage()`（约 line 452-461）
- **内容**：在提取 `prompt_tokens`/`completion_tokens` 之后，增加 `input_tokens`/`output_tokens` 的 fallback 读取
- **影响**：imgGen、imgEdit 立即生效

### P2：rerank total_tokens 适配

- **文件**：`BaseAdapter.recordTokenUsage()`
- **内容**：当 `prompt_tokens` 和 `completion_tokens` 都为 0 但 `total_tokens` > 0 时，将 `total_tokens` 作为 promptTokens（或按次计费）
- **影响**：rerank 计费生效

### P3：TTS 按次/按时长计费

- **文件**：`BaseAdapter`、`BillingService`、`ModelPricingService`
- **内容**：TTS 返回 byte[] 无法提取 token，需引入「按次计费」模式（每次固定金额）或从请求参数提取 input 字符数
- **影响**：TTS 计费生效

### P4：STT 按次/按时长计费

- **文件**：同 P3
- **内容**：STT 上游不返回 usage，需引入「按次计费」或按音频时长计费
- **影响**：STT 计费生效

### P5：平台 modelType 扩展（需算力平台配合）

- **内容**：算力平台新增 modelType 7（STT）和 8（imgEdit），或在 ai_channel 表增加 sttUrl / imgEditUrl 字段
- **影响**：STT 和 imgEdit 可从算力平台同步实例，不再依赖 YAML

### P6：vidGen 端点实现（长期）

- **内容**：补齐 Controller 端点 + ServiceCapability 方法 + BaseAdapter 实现 + DTO
- **前提**：算力平台 ai_channel 表增加 videoUrl 字段

---

## 七、Review 结论（2025-06-05 代码逐行验证）

### ✅ 确认正确的部分

1. **Section 一~二（架构共性）**：全部正确。7 个端点共享管道，`selectInstance()`、`getAdapter()`、`balanceCheck`、`recordBilling` 对所有 serviceType 一视同仁。

2. **Section 三·路由层**：
   - `resolveChannelServiceUrl` 6 种映射 ✅（代码 switch "1"~"6" + 字符串别名，确认无 STT/imgEdit 映射）
   - `detectPath` 兜底路径 ✅（代码 default → `/v1/chat/completions`，确认无 STT/imgEdit 条目）
   - `detectServiceKey` 中 STT/imgEdit 有字符串条目（"stt"→"stt", "img-edit"→"img-edit"）但无对应数字编码 ✅
   - YAML 兜底 7 种类型全有配置 ✅
   - vidGen 的 `ai_channel` 无 `videoUrl` 字段 ✅（代码注释 `// ai_channel 暂无 videoUrl 字段`）

3. **Section 四·计费层核心结论**：所有「cost 恒为 0」的结论方向正确。

4. **Section 五·总评**：🟢🟡🟠 评级准确。

5. **Section 六·待办 P1-P6**：优先级排列合理，P1（字段名对齐）确实是改动最小收益最大的。

### ⚠️ 需修正/补充的细节（共 6 处）

#### 修正 1：imgGen/imgEdit usage 格式描述不完整

**原文**：`{ input_tokens, output_tokens }`

**实际代码**（`ImageGenerateDTO.Response.Usage`）：
```java
public record Usage(
    Integer total_tokens,
    Integer input_tokens,
    Integer output_tokens,
    InputTokensDetails input_tokens_details   // { text_tokens, image_tokens }
) {}
```

`total_tokens` 字段存在且**已被代码成功提取**（`usage.path("total_tokens").asLong(0)` 能读到值）。
但计费公式只使用 `prompt_tokens × inputPrice + completion_tokens × outputPrice`，不使用 `total_tokens`。
所以结论（cost=0）不变，但原因是「`total_tokens` 被提取但未参与计费，`input_tokens`/`output_tokens` 字段名不匹配导致 prompt/completion 为 0」。

**更新后的计费表 imgGen/imgEdit 行**：

| 接口 | 上游响应 usage 格式 | prompt_tokens | completion_tokens | total_tokens | 计费结果 |
|------|---|---|---|---|---|
| imgGen | `{ total_tokens, input_tokens, output_tokens, ... }` | =0（不匹配） | =0（不匹配） | ✅ 有值但不参与计费 | cost=0 |
| imgEdit | 同上 | =0（不匹配） | =0（不匹配） | ✅ 有值但不参与计费 | cost=0 |

#### 修正 2：rerank 的 total_tokens 已被提取，只是没用于计费

**原文**：「代码只读 `prompt_tokens`/`completion_tokens`，都读不到」

**实际**：`total_tokens` 是能读到的（代码 `usage.path("total_tokens").asLong(0)` 正常工作），`totalTokens` 变量有值。
问题在于 `BillingService.buildRecord()` 的计费公式 `inputPrice × promptTokens + outputPrice × completionTokens` 根本不使用 `totalTokens` 变量。
所以 rerank 的修复方向应该是「让计费公式使用 totalTokens」，而不是「先提取 totalTokens」。

#### 修正 3：缺少 RouterResponse 包装层说明

**原文未提及的关键机制**：`NonStreamingRequestProcessor.processJsonResponse()` 将所有 JSON 响应包装在 `RouterResponse` 中：

```json
{ "success": true, "message": "请求成功", "data": { /* 下游原始响应 */ }, "timestamp": "..." }
```

`recordTokenUsage` 中 `node.path("data")` 实际找到的是 `RouterResponse.data`（即下游原始响应），再从中找 `usage`。
这解释了为什么「`data.usage` 路径能工作」——不是上游返回的 JSON 有 `data` 字段，而是 `RouterResponse` 的包装。
**TTS 的 `byte[]` 不经过 `processJsonResponse`**，走 `processBinaryResponse` 返回 `ResponseEntity<byte[]>`（无 RouterResponse 包装），所以 `objectMapper.valueToTree(byte[])` 产生的是 Base64 字符串节点而非 JSON 对象。

#### 修正 4：DTO Response 类未参与实际反序列化

各 DTO 中定义的 Response record（如 `ChatDTO.Response`、`RerankDTO.Response`）**只作为类型定义/文档**，
实际响应解析全部通过 `objectMapper.valueToTree()` 转为泛型 `JsonNode` 后手动路径导航。
因此 DTO 中的 `@JsonProperty` 注解不影响 token 提取行为——真正决定能否提取的是**上游服务返回的原始 JSON 字段名**。

#### 修正 5：detectPath 的 default 兜底是假想场景

原文说「`detectPath()` 中 stt/imgEdit 无条目，default 兜底到 `/v1/chat/completions`（路径错误）」。
这个描述**技术上正确**但实际不会触发——因为算力平台根本不会产出 modelType 为 "stt" 或 "img-edit" 的模型（平台只有 1-6 六种数字编码）。
真正的问题是「STT/imgEdit 没有平台 modelType，不可能从平台同步这类实例」。
只有当未来算力平台扩展了新的 modelType（如 "7"=STT）而 `detectPath` 忘记加对应条目时，才会命中这个 default 兜底问题。

#### 修正 6：P2 修复描述需调整

原文：「当 prompt_tokens 和 completion_tokens 都为 0 但 total_tokens > 0 时，将 total_tokens 作为 promptTokens」
更准确的描述：`total_tokens` **已被提取**（变量 `totalTokens` 有值），修复方向是让 `BillingService.buildRecord()` 的计费公式**使用 `totalTokens`**（例如当 prompt+completion=0 但 total>0 时，用 total 替代），而不需要在 `recordTokenUsage` 中再做提取。

### 📊 Review 后的修正总结

| # | 原计划内容 | 实际代码 | 影响 |
|---|---|---|---|
| 1 | imgGen/imgEdit usage 只有 input/output_tokens | 还有 total_tokens 且已被提取 | 结论不变，原因描述需修正 |
| 2 | rerank total_tokens 读不到 | 已读到，只是没用于计费 | P2 修复方向调整 |
| 3 | 未提及 RouterResponse 包装 | 所有 JSON 响应都经 RouterResponse 包装 | 补充关键架构上下文 |
| 4 | 未说明 DTO Response 未参与反序列化 | 全部走 JsonNode 泛型解析 | 补充理解上下文 |
| 5 | detectPath 兜底是当前问题 | 是假想场景，当前不会触发 | 严重度降级 |
| 6 | P2 需要提取 total_tokens | 已提取，需改计费公式 | P2 修复步骤简化 |

---

## 八、多模态/视觉模型支持分析（2026-06-05）

### 8.1 四类模型 × 涉及接口

当前网关需要支持的四类模型，涉及 **3 个接口**：

| 模型类型 | 接口 | platform modelType | 路由 | 计费 | 状态 |
|---------|------|---|------|------|------|
| 文本模型 | `/api/v1/chat/completions` | 1 | 🟢 | 🟢 正确 | **已支持** |
| 向量模型 | `/api/v1/embeddings` | 5 | 🟢 | 🟢 正确 | **已支持** |
| 视觉模型 | `/api/v1/images/generations` | 2 | 🟢 | 🟡 cost=0（字段名不匹配） | **路由可用，计费缺失** |
| 多模态模型 | `/api/v1/chat/completions` | 1 | 🟢 | ⚠️ 待验证 | **❌ 不支持** |

> - **视觉模型**（文生图）走 `/api/v1/images/generations`，路由正常，但计费 cost=0（详见 Section 四·P1）
> - **多模态模型**（文本+图片输入）走 `/api/v1/chat/completions`，请求中 `messages` 的 `content` 需支持数组格式（含 `image_url` 部分），当前 DTO 不支持

### 8.2 各类型不支持/不完整的原因

#### 视觉模型（`/api/v1/images/generations`）：路由可用，计费缺失

路由正常（platform modelType=2 → imgGen），但 **cost 恒为 0**（详见 Section 四）：

- 上游返回 `input_tokens` / `output_tokens`，代码读的是 `prompt_tokens` / `completion_tokens`，字段名对不上
- 已归入 Section 六·P1，此处不再重复分析

#### 多模态模型（`/api/v1/chat/completions`）：DTO 层阻塞

`ChatDTO.Message.content` 类型为 `String`，无法承载 OpenAI 多模态格式：

```java
// ChatDTO.java:249-253 — 当前定义
public record Message(
    String role,
    String content,   // ← 只能接受字符串
    String name
) {}
```

OpenAI 多模态格式要求 `content` 同时支持**字符串**和**数组**：

```json
// 文本模型（当前支持 ✅）
{ "role": "user", "content": "你好" }

// 多模态模型（当前不支持 ❌）
{ "role": "user", "content": [
    {"type": "text", "text": "这张图里有什么"},
    {"type": "image_url", "image_url": {"url": "https://..."}}
]}
```

**数据丢失链路**：

```
请求 JSON → Controller(@RequestBody ChatDTO.Request) → Jackson 反序列化
  → content 为数组 → 无法转为 String → 反序列化失败或数据丢失
  → 所有适配器从 DTO 重建请求体（objectMapper.valueToTree(request.messages())）
  → 上游收到的 content 为 null/空/toString 残留
```

**所有适配器都是从 DTO 重建请求体**，不是原始 body 透传：

| 适配器 | 代码位置 | 行为 |
|--------|---------|------|
| `NormalOpenAiAdapter` | `:140` `openAiRequest.set("messages", objectMapper.valueToTree(request.messages()))` | 从 DTO 重建 |
| `OllamaAdapter` | `:180` `ollamaRequest.set("messages", objectMapper.valueToTree(request.messages()))` | 从 DTO 重建 |
| `VllmAdapter` | `:150` 同上 | 从 DTO 重建 |
| `GpuStackAdapter` | `:138` 同上 | 从 DTO 重建 |

即使绕过 DTO 反序列化问题，适配器重建时也会丢失 DTO 未映射的字段。

### 8.3 修复方案

#### 视觉模型：沿用 Section 六·P1

字段名对齐（`input_tokens`/`output_tokens` fallback 读取），改动最小收益最大，无需额外分析。

#### 多模态模型：以下两个方案二选一

##### 方案 A：改 DTO 类型（推荐，改动小、风险可控）

| 步骤 | 改动内容 | 涉及文件 |
|------|---------|---------|
| 1 | `ChatDTO.Message.content` 从 `String` → `Object` | `ChatDTO.java` |
| 2 | 所有读 `.content()` 的地方兼容 `String` 和 `List` 两种类型 | 适配器 + 可能的工具类 |
| 3 | `NormalOpenAiAdapter` / `VllmAdapter` / `GpuStackAdapter` 序列化时正确处理 `Object content` | 3 个适配器 |
| 4 | `OllamaAdapter` 特殊处理：content 为数组时拼接 text 部分为字符串（Ollama 不支持多模态格式） | `OllamaAdapter.java` |

**优点**：改动集中，只改 DTO + 适配器，不影响其他接口
**风险**：后续新增字段仍需同步改 DTO

##### 方案 B：原始 body 透传（长期方向）

| 步骤 | 改动内容 | 涉及文件 |
|------|---------|---------|
| 1 | Controller 层保留原始 JSON body | `UniversalController.java` |
| 2 | 适配器默认透传原始 body，仅需要字段转换时才重建（如 Ollama） | `BaseAdapter` + 所有适配器 |
| 3 | token/model 提取仍从 DTO 读取 | 不影响 |

**优点**：一劳永逸，未来新字段自动透传
**缺点**：改动面大，需重构适配器基类

#### 建议

先走**方案 A** 满足多模态需求，后续有更多字段透传需求时再升级为方案 B。

### 8.4 待办汇总

| # | 内容 | 关联模型类型 | 优先级 | 前置依赖 |
|---|------|------------|--------|---------|
| P1 | imgGen/imgEdit 字段名对齐（`input_tokens`/`output_tokens` fallback） | 视觉模型 | P1 | 无 |
| P7-1 | `ChatDTO.Message.content` 改为 `Object` 类型 | 多模态模型 | P1 | 无 |
| P7-2 | 适配器序列化兼容 `Object content` | 多模态模型 | P1 | P7-1 |
| P7-3 | OllamaAdapter 处理 content 数组（拼接 text） | 多模态模型 | P1 | P7-1 |
| P7-4 | 联调验证多模态请求 token 计费 | 多模态模型 | P2 | P7-1~3 |

### 8.5 API Key 过期提示修复（已完成）

本次分析同期修复了 API Key 认证链路的两个提示问题：

| 问题 | 修复前 | 修复后 | 涉及文件 |
|------|--------|--------|---------|
| 过期 key 提示错误 | `"API Key在算力平台存在但无法提取身份信息"` | `"API Key已过期，过期时间: 2026-06-05 14:25:00"` | `PlatformDataSyncService.java`（新增 `lookupApiKey` + `ApiKeyLookupResult`） |
| 不存在 key 提示误导 | 同上（"存在但无法提取"） | `"无效的API Key"` | `CustomReactiveAuthenticationManager.java`（三分支处理：过期/不存在/有效） |
| 负缓存返回格式错误 | `"API Key 格式无效，必须以 'sk-' 开头"` | `"无效的API Key"` | `CustomReactiveAuthenticationManager.java:132` |

**核心改动**：`PlatformDataSyncService.getUserIdentityByApiKey()` 中 `isExpired` 检查从解密匹配**之前**移到**之后**，匹配成功后再判断过期状态，通过 `ApiKeyLookupResult` 区分三种结果（过期/不存在/有效）。
