# 免费 Tokens 额度设计文档

> 版本：v1.0  
> 日期：2026-06-24  
> 状态：待实现

## 1. 背景与目标

算力平台希望为每个用户提供一定量的免费体验 tokens，用于文本类模型调用。JAiRouter 作为中转代理，负责：

1. 维护每个用户的免费 tokens 剩余额度；
2. 请求时优先使用免费额度；
3. 免费额度不足以覆盖本次请求实际用量时，本次请求直接失败（不透支、不扣余额）；
4. 免费额度耗尽后，后续请求切换到现有余额校验与扣减逻辑；
5. 命中免费额度的账单，在算力平台后台展示时将 API Key、金额等字段映射为 “-”。

## 2. 核心原则

- **按用户维度统计**：免费额度按 `sldd_system_users.user_id` 统计（与 `UserIdentity.userId` 一致）。
- **仅文本类服务**：`chat`、`embedding`、`rerank` 计入免费额度；其他服务类型直接走余额逻辑。
- **不允许透支**：`remaining_quota` 不能扣到 0 或负数。
- **请求后按实际用量判断**：非流式请求拿到响应后判断；流式请求在流式结束时按累计 tokens 判断。
- **超支即锁定**：一旦实际用量 > 剩余额度，立即设置 `trial_exhausted=true`，后续请求直接走余额逻辑，防止反复发起大用量请求薅平台算力。
- **余额兜底**：剩余额度 ≤ 0 后，请求走现有余额校验（余额 ≥ 0 放行，余额 < 0 返回 402）。
- **后台展示**：剩余额度由算力平台后台直接读取 `ai_user_free_quota`；命中免费额度的账单记录 `is_free_quota=true`，金额字段存 0，后台映射为 “-”。

## 3. 数据模型

### 3.1 新增表 `ai_user_free_quota`

由算力平台预创建并初始化，JAiRouter 只读取和更新。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | 自增主键 |
| `user_id` | VARCHAR(64) | NOT NULL, UNIQUE | `sldd_system_users.user_id` |
| `total_quota` | BIGINT | NOT NULL DEFAULT 1000000 | 免费额度总量 |
| `used_quota` | BIGINT | NOT NULL DEFAULT 0 | 已用额度 |
| `remaining_quota` | BIGINT | NOT NULL DEFAULT 1000000 | 剩余额度 |
| `trial_exhausted` | BOOLEAN | NOT NULL DEFAULT false | 是否已触发超支失败 |
| `exhausted_at` | TIMESTAMP | | 触发超支失败的时间 |
| `create_time` | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | 最后更新时间 |
| `creator` | VARCHAR(64) | | 创建者（兼容 BaseDO 规范） |
| `updater` | VARCHAR(64) | | 更新者（兼容 BaseDO 规范） |
| `deleted` | BOOLEAN | NOT NULL DEFAULT false | 是否删除（逻辑删除） |
| `version` | BIGINT | NOT NULL DEFAULT 0 | 乐观锁版本号 |

**说明**：
- 记录不存在视为该用户没有免费额度，直接走余额逻辑。
- `remaining_quota` 由算力平台维护为 `total_quota - used_quota`，JAiRouter 更新时同步修改 `used_quota` 与 `remaining_quota`。
- `version` 用于乐观锁，避免并发请求导致额度扣减丢失。
- **`trial_exhausted` 是防薅羊毛的关键字段**：一旦用户某次请求的实际用量超过剩余额度，立即标记为 true，后续请求不再尝试免费额度，直接切换为余额逻辑。
- 审计字段 `create_time` / `update_time` / `creator` / `updater` / `deleted` 兼容算力平台 `BaseDO` 规范。

### 3.3 建表 DDL（PostgreSQL）

```sql
CREATE TABLE IF NOT EXISTS ai_user_free_quota (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    total_quota     BIGINT NOT NULL DEFAULT 1000000,
    used_quota      BIGINT NOT NULL DEFAULT 0,
    remaining_quota BIGINT NOT NULL DEFAULT 1000000,
    trial_exhausted BOOLEAN NOT NULL DEFAULT false,
    exhausted_at    TIMESTAMP,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator         VARCHAR(64),
    updater         VARCHAR(64),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_ai_user_free_quota_user_id UNIQUE (user_id)
);

CREATE INDEX idx_ai_user_free_quota_user_id ON ai_user_free_quota (user_id);

-- 字段注释
COMMENT ON TABLE ai_user_free_quota IS '用户免费 token 额度表';
COMMENT ON COLUMN ai_user_free_quota.id IS '自增主键';
COMMENT ON COLUMN ai_user_free_quota.user_id IS '用户ID，对应 sldd_system_users.user_id';
COMMENT ON COLUMN ai_user_free_quota.total_quota IS '免费额度总量(tokens)';
COMMENT ON COLUMN ai_user_free_quota.used_quota IS '已用免费额度(tokens)';
COMMENT ON COLUMN ai_user_free_quota.remaining_quota IS '剩余免费额度(tokens)';
COMMENT ON COLUMN ai_user_free_quota.trial_exhausted IS '是否已触发超支失败：true=已锁定，后续请求走余额逻辑';
COMMENT ON COLUMN ai_user_free_quota.exhausted_at IS '触发超支失败的时间';
COMMENT ON COLUMN ai_user_free_quota.create_time IS '创建时间';
COMMENT ON COLUMN ai_user_free_quota.update_time IS '最后更新时间';
COMMENT ON COLUMN ai_user_free_quota.creator IS '创建者';
COMMENT ON COLUMN ai_user_free_quota.updater IS '最后更新者';
COMMENT ON COLUMN ai_user_free_quota.deleted IS '是否删除：false=未删除，true=已逻辑删除';
COMMENT ON COLUMN ai_user_free_quota.version IS '乐观锁版本号';

-- ai_billing_record 新增字段
ALTER TABLE ai_billing_record
    ADD COLUMN IF NOT EXISTS is_free_quota BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ai_billing_record
    ADD COLUMN IF NOT EXISTS free_quota_consumed BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN ai_billing_record.is_free_quota IS '是否命中免费额度：true=本次请求走免费额度，金额字段为0';
COMMENT ON COLUMN ai_billing_record.free_quota_consumed IS '本次实际消耗的免费 token 数';
```

**说明**：
- `ai_user_free_quota` 由算力平台预初始化，`JAiRouter` 仅执行扣减与锁定更新。
- `deleted` 为逻辑删除字段，查询时默认过滤 `deleted = false`。
- `version` 用于乐观锁并发控制。

### 3.4 常用 DML（PostgreSQL）

#### 3.4.1 初始化用户免费额度（算力平台执行）

```sql
-- 示例：为用户 'u123456' 初始化 100 万免费 tokens
INSERT INTO ai_user_free_quota (
    user_id,
    total_quota,
    used_quota,
    remaining_quota,
    trial_exhausted,
    create_time,
    update_time,
    creator,
    updater,
    deleted,
    version
) VALUES (
    'u123456',
    1000000,
    0,
    1000000,
    false,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system',
    'system',
    false,
    0
);
```

#### 3.4.2 查询用户剩余免费额度

```sql
-- JAiRouter 请求前校验使用
SELECT id,
       user_id,
       total_quota,
       used_quota,
       remaining_quota,
       trial_exhausted,
       version
FROM ai_user_free_quota
WHERE user_id = 'u123456'
  AND deleted = false;
```

#### 3.4.3 命中免费额度：原子扣减用量

```sql
-- 在代码中先读取当前 remaining_quota 与 version，再执行乐观锁更新
UPDATE ai_user_free_quota
SET used_quota      = used_quota + :tokens,
    remaining_quota = remaining_quota - :tokens,
    update_time     = CURRENT_TIMESTAMP,
    updater         = :updater,
    version         = version + 1
WHERE user_id = :userId
  AND version = :version
  AND deleted = false;
```

#### 3.4.4 超支失败：原子锁定 trial_exhausted

```sql
-- 实际用量 > 剩余额度时，原子设置 trial_exhausted=true
UPDATE ai_user_free_quota
SET trial_exhausted = true,
    exhausted_at    = CURRENT_TIMESTAMP,
    update_time     = CURRENT_TIMESTAMP,
    updater         = :updater,
    version         = version + 1
WHERE user_id = :userId
  AND version = :version
  AND deleted = false;
```

#### 3.4.5 查询命中免费额度的账单

```sql
-- 算力平台后台查询试用账单，映射金额字段为 '-'
SELECT id,
       user_id,
       user_account,
       api_key_id,
       api_key_name,
       model_name,
       service_type,
       total_tokens,
       is_free_quota,
       free_quota_consumed,
       original_cost,
       discount_amount,
       total_cost
FROM ai_billing_record
WHERE is_free_quota = true
  AND is_deleted = false
ORDER BY started_at DESC;
```

#### 3.4.6 查询用户免费额度使用统计

```sql
-- 算力平台后台展示剩余额度与使用率
SELECT user_id,
       total_quota,
       used_quota,
       remaining_quota,
       ROUND(used_quota * 100.0 / total_quota, 2) AS usage_rate,
       trial_exhausted,
       exhausted_at
FROM ai_user_free_quota
WHERE user_id = 'u123456'
  AND deleted = false;
```

### 3.2 `ai_billing_record` 新增字段

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `is_free_quota` | BOOLEAN | NOT NULL DEFAULT false | 是否命中免费额度 |
| `free_quota_consumed` | BIGINT | NOT NULL DEFAULT 0 | 本次实际消耗的免费 token 数 |

**说明**：
- 命中免费额度时：`is_free_quota=true`、`free_quota_consumed=本次 totalTokens`、`original_cost=0`、`discount_amount=0`、`total_cost=0`。
- API Key ID / Name 仍正常存储，仅金额字段为 0。
- 算力平台后台查询时，根据 `is_free_quota=true` 将 API Key、API Key 描述、计费金额、折扣优惠金额、账单金额映射为 “-”。

## 4. 配置项

新增 `FreeQuotaProperties`：

```yaml
jairouter:
  billing:
    free-quota:
      enabled: true                       # 是否开启免费额度
      default-total: 1000000              # 默认总量（tokens）
      service-types: chat,embedding,rerank # 计入免费额度的服务类型
```

- `enabled`：关闭时所有请求走余额逻辑，不查询免费额度。
- `default-total`：算力平台未配置总量时的默认值（实际由算力平台写入表）。
- `service-types`：逗号分隔，大小写不敏感。

## 5. 核心服务设计

### 5.1 FreeQuotaService

职责单一：查询剩余额度、判定是否命中免费额度、执行原子扣减。

```java
public interface FreeQuotaService {
    /** 查询剩余额度（仅文本类服务生效） */
    long getRemainingQuota(String userId, String serviceType);

    /** 请求后扣减免费额度 */
    FreeQuotaResult deductQuota(String userId, String serviceType, long totalTokens);
}

public record FreeQuotaResult(
    boolean hitFreeQuota,      // 是否命中免费额度
    long deductedTokens,       // 实际扣除的 tokens
    long remainingBefore,      // 扣减前剩余
    long remainingAfter        // 扣减后剩余
) {}
```

#### 扣减逻辑

1. 若 `serviceType` 不在配置列表中，返回 `miss()`。
2. 查询 `ai_user_free_quota`：
   - 记录不存在 → `miss()`。
   - `remaining_quota <= 0` → `miss()`。
   - `trial_exhausted = true` → `miss()`。
3. 若 `totalTokens <= remaining_quota`：
   - 乐观锁更新：`used_quota += totalTokens`，`remaining_quota -= totalTokens`，`version += 1`。
   - 返回 `hit(deducted=totalTokens)`。
4. 若 `totalTokens > remaining_quota`：
   - 原子设置 `trial_exhausted = true`，`exhausted_at = now()`，`version += 1`；
   - 返回 `miss()`，调用方负责返回 402 失败。

### 5.2 BalanceCheckService 改造

请求前校验流程：

```
if (!identity.platformUser()) → 跳过
if (!freeQuotaService.isFreeQuotaServiceType(serviceType)) → 走余额校验

FreeQuota quota = freeQuotaService.findByUserId(userId)
if (quota == null || quota.remaining <= 0 || quota.trialExhausted) → 走余额校验
else → 放行（本次可能走免费额度）
```

**说明**：
- `trialExhausted = true` 后，该用户后续请求不再尝试免费额度，直接切换为余额逻辑。
- 只要剩余额度 > 0 且未触发超支，请求即放行，不预估本次用量。
- 余额校验仅在剩余额度 ≤ 0 或已超支锁定时执行。

### 5.3 BillingService 改造

计费流程：

```
构建 BillingRecordEntity（先按现有逻辑计算价格）

if (platformUser && 是文本类服务 && 请求前未超支锁定):
    result = freeQuotaService.deductQuota(userId, serviceType, totalTokens)
    if (result.hitFreeQuota()):
        record.isFreeQuota = true
        record.freeQuotaConsumed = result.deductedTokens()
        record.originalCost = 0
        record.discountAmount = 0
        record.totalCost = 0
        // 不调用余额扣减
    else:
        // 实际用量 > 剩余额度，已原子设置 trial_exhausted=true
        record.isSuccess = false
        record.errorCode = "FREE_QUOTA_EXHAUSTED"
        record.errorMessage = "体验额度耗尽，生成失败"
        // 不保存账单？或保存一条失败账单？建议保存失败账单用于审计
        throw ResponseStatusException(402, "体验额度耗尽，生成失败")
else:
    // 走余额逻辑
    保存账单
    调用 BalanceDeductionService
```

**关键说明**：
- 命中免费额度时，金额字段置 0，不扣余额。
- 未命中时，`FreeQuotaService` 已原子设置 `trial_exhausted=true`；调用方必须阻止响应返回给客户端，返回 402。
- 失败账单是否保存可讨论；建议保存，便于后台排查“为什么生成失败”。

## 6. 响应截断策略

### 6.1 非流式请求

- 先完整接收上游响应；
- 提取 `usage.total_tokens`；
- 若实际用量 > 剩余额度：丢弃响应体，向客户端返回 402 “体验额度耗尽，生成失败”；
- 若实际用量 ≤ 剩余额度：扣减额度并返回正常响应。

### 6.2 流式请求

- `StreamingRequestProcessor` 在流式结束时累计本次总 tokens；
- 若累计 tokens > 剩余额度：
  - 已发送的 chunk 无法撤回，终端会收到部分响应；
  - 发送一个 SSE 错误事件（如 `data: {"error":{"message":"体验额度耗尽，生成失败","code":"free_quota_exhausted"}}`）后结束流；
  - 不扣减免费额度，不扣余额。
- 若累计 tokens ≤ 剩余额度：扣减额度并正常结束。

**说明**：流式场景下“截断响应”本质上是中断后续输出，已输出内容无法回收。产品侧接受此行为。

## 7. 并发控制

使用乐观锁 + 有限重试：

```sql
-- 命中免费额度：扣减用量
UPDATE ai_user_free_quota
SET used_quota = used_quota + :tokens,
    remaining_quota = remaining_quota - :tokens,
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE user_id = :userId AND version = :version;

-- 超支失败：锁定 trial_exhausted
UPDATE ai_user_free_quota
SET trial_exhausted = true,
    exhausted_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE user_id = :userId AND version = :version;
```

- 每次扣减前读取当前 `remaining_quota`、`trial_exhausted` 和 `version`；
- 超支失败时必须原子设置 `trial_exhausted=true`，避免并发下多个大用量请求重复触发；
- 更新失败（版本冲突）时重试，最多 3 次；
- 重试均失败时回退到余额逻辑（避免无限阻塞计费异步线程）。

## 8. 后台查询

- 算力平台后台直接读取 `ai_user_free_quota` 表展示剩余额度。
- 账单明细读取 `ai_billing_record`，根据 `is_free_quota=true` 映射金额字段为 “-”。
- 可选：JAiRouter 提供内部接口 `/internal/free-quota/{userId}`（HMAC 签名），供算力平台跨实例查询；本期先不实现，后续按需补充。

## 9. 边界情况

| 场景 | 处理 |
|---|---|
| 用户无免费额度记录 | 走余额逻辑。 |
| 剩余额度为 0 | 走余额逻辑。 |
| 实际用量 = 剩余额度 | 全部扣减，剩余 0，账单命中免费额度。 |
| 实际用量 > 剩余额度 | 原子设置 `trial_exhausted=true`，返回 402 失败，不扣额度。 |
| 请求失败（上游 4xx/5xx/超时） | 不扣减免费额度（失败不消耗额度），不设置 `trial_exhausted`。 |
| 并发请求 | 乐观锁重试，确保额度扣减准确。 |
| 服务类型非文本类 | 直接走余额逻辑。 |

## 10. 测试策略

- `FreeQuotaServiceTest`
  - 命中免费额度扣减；
  - 实际用量 > 剩余额度返回 miss；
  - 服务类型过滤；
  - 并发扣减无超支；
  - 乐观锁冲突重试。
- `BalanceCheckServiceTest`
  - 有剩余额度直接放行；
  - 剩余额度 ≤ 0 转余额校验；
  - 非文本类服务走余额校验。
- `BillingServiceTest`
  - 命中免费额度账单金额置 0 且不扣余额；
  - 未命中时返回 402 且不扣余额；
  - 免费额度耗尽后正常余额扣减。
- 集成测试（可选）
  - 模拟一次 chat 非流式请求，验证 `ai_user_free_quota` 与 `ai_billing_record` 状态一致。

## 11. 风险与待确认事项

1. **流式请求截断体验**：已输出的 chunk 无法回收，客户端可能看到半截内容后收到错误。
2. **非流式请求上游成本**：即使返回 402，上游模型已生成完整响应，平台已承担该成本；`trial_exhausted` 标记确保该用户不会重复触发。
3. **失败账单是否保存**：建议保存 `is_success=false` 的失败账单，便于后台排查“为什么生成失败”。
4. **免费额度耗尽后的首次余额请求**：余额 ≥ 0 时放行，扣减后可能变负，与现有逻辑一致。
5. **薅羊毛风险**：已通过 `trial_exhausted` 锁定解决；未锁定前单次超支失败的平台成本为上限。

## 12. 下一步

1. 创建 `FreeQuotaEntity`、`FreeQuotaRepository`、`FreeQuotaService`、`FreeQuotaProperties`。
2. 修改 `BalanceCheckService` 请求前校验逻辑（增加 `trialExhausted` 判断）。
3. 修改 `BillingService` 计费与扣减逻辑。
4. 修改 `StreamingRequestProcessor` 与 `NonStreamingRequestProcessor` 支持按实际用量失败。
5. 修改 `ai_billing_record` 实体与 DDL。
6. 补充单元测试与集成测试。
