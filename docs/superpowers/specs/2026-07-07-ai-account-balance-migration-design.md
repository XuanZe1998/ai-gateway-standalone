# ai_account_balance 余额/预警表迁移设计

## 1. 背景与目标

算力平台调整后：`ai_enterprise` 表只保留白名单和企业折扣能力，余额、预警相关字段迁移到新表 `ai_account_balance`。

**本设计目标**：将 JAiRouter 中所有余额读取、余额扣减、低余额预警逻辑从 `ai_enterprise` 迁移到 `ai_account_balance`，扣减口径和预警规则保持与原来一致。

## 2. 表职责划分

| 表 | 保留字段 | 迁移字段 |
|---|---|---|
| `ai_enterprise` | `id`, `company_id`, `description`, `subsidy_discount`, 审计字段 | `balance`, `warn_enabled`, `warn_threshold`, `last_warn_time`, `last_warn_threshold`, `arrear_status` |
| `ai_account_balance` | 全部新字段 | — |

说明：
- `subsidy_discount` 继续留在 `ai_enterprise`，供 `DiscountCalculationService` 查询企业补贴折扣。
- `arrear_status` 已废弃，业务逻辑早已改为直接按 `balance < 0` 判断，本次一并清理。

## 3. 新表结构

```sql
CREATE TABLE ai_account_balance (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          VARCHAR(64)   NOT NULL,
    account_type        INTEGER       NOT NULL DEFAULT 1,
    balance             NUMERIC(20,6) NOT NULL DEFAULT 0,
    warn_enabled        BOOLEAN       NOT NULL DEFAULT false,
    warn_threshold      NUMERIC(20,6),
    last_warn_time      TIMESTAMP,
    last_warn_threshold NUMERIC(20,6),
    creator             VARCHAR(64),
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater             VARCHAR(64),
    update_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             BOOLEAN       NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_ai_account_balance_active
    ON ai_account_balance (account_id, account_type)
    WHERE deleted = false;
```

账户映射规则：
- **企业用户**：`accountId = sldd_system_users.company_id`，`accountType = 1`
- **个人用户**：`accountId = sldd_system_users.user_id`，`accountType = 2`

## 4. 方案选择

采用 **方案 A：新增 `ai_account_balance` 实体/Repository，Service 层组合查询**。

理由：
- 表职责清晰，符合算力平台新设计。
- 改动量适中，现有 `EnterpriseInfo`、`DiscountCalculationService` 结构基本保留。
- 对个人用户启用余额账户的扩展自然。

## 5. 新增实体与 Repository

### 5.1 `PlatformAccountBalanceEntity`

路径：`persistence/jpa/entity/platform/`

完整映射 `ai_account_balance` 所有字段，包括审计字段和 `last_warn_threshold`。

### 5.2 `PlatformAccountBalanceRepository`

路径：`persistence/jpa/repository/platform/`

核心方法：

```java
Optional<PlatformAccountBalanceEntity> findByAccountIdAndAccountTypeAndDeletedFalse(
    String accountId, Integer accountType);

@Modifying
@Query("UPDATE PlatformAccountBalanceEntity b SET b.balance = b.balance - :cost, " +
       "b.updateTime = CURRENT_TIMESTAMP WHERE b.id = :id AND b.deleted = false")
int deductBalance(@Param("id") Long id, @Param("cost") BigDecimal cost);

@Query(value = "SELECT balance FROM ai_account_balance WHERE id = :id", nativeQuery = true)
Optional<BigDecimal> findRealBalanceById(@Param("id") Long id);

@Modifying
@Query("UPDATE PlatformAccountBalanceEntity b SET b.lastWarnThreshold = NULL, " +
       "b.updateTime = CURRENT_TIMESTAMP WHERE b.id = :id AND b.lastWarnThreshold IS NOT NULL")
int clearLastWarnThreshold(@Param("id") Long id);

@Modifying
@Query("UPDATE PlatformAccountBalanceEntity b SET b.lastWarnThreshold = :threshold, " +
       "b.lastWarnTime = CURRENT_TIMESTAMP, b.updateTime = CURRENT_TIMESTAMP " +
       "WHERE b.id = :id AND (" +
       "  b.lastWarnThreshold IS NULL " +
       "  OR b.lastWarnThreshold != :threshold " +
       "  OR :balanceBeforeDeduction > b.lastWarnThreshold" +
       ")")
int updateLastWarnThreshold(@Param("id") Long id,
                            @Param("threshold") BigDecimal threshold,
                            @Param("balanceBeforeDeduction") BigDecimal balanceBeforeDeduction);
```

## 6. Service 层改造

### 6.1 `PlatformEnterpriseEntity` / `PlatformEnterpriseRepository` 调整

`PlatformEnterpriseEntity` 删除字段：
- `balance`, `warnEnabled`, `warnThreshold`, `lastWarnTime`, `lastWarnThreshold`, `arrearStatus`

保留字段：
- `id`, `companyId`, `description`, `subsidyDiscount`, 审计字段

`PlatformEnterpriseRepository` 删除余额/预警相关方法，仅保留企业基础查询。

### 6.2 `EnterpriseLookupService` 改造

对外接口保持不变：
- `lookupByUserId(Long platformUserId)`
- `lookupByEnterpriseId(Long enterpriseId)`

内部实现：
- 企业名称、`companyId`、`subsidyDiscount` 从 `ai_enterprise` 读取。
- `balance`、`warnEnabled`、`warnThreshold`、`lastWarnTime`、`lastWarnThreshold` 从 `ai_account_balance` 读取。

新增内部方法：

```java
Optional<EnterpriseInfo> lookupByAccount(String accountId, Integer accountType);
```

### 6.3 `BalanceCheckService` 改造

企业用户和个人用户统一按 `ai_account_balance.balance` 校验：

1. 文本类服务优先检查免费额度，有则放行。
2. 按 `userType` 确定 `accountId` / `accountType`：
   - `userType = 1`（企业）：`accountId = companyId`，`accountType = 1`
   - `userType = 2`（个人）：`accountId = userId`，`accountType = 2`
3. 调用 `EnterpriseLookupService.lookupByAccount(accountId, accountType)` 查询余额/预警配置。
4. `balance <= 0` 返回 402，`balance > 0` 放行。

> 注意：不再以 `enterpriseId != null` 判断是否有余额账户。`userType = 2` 的个人用户也启用余额账户。

口径保持和原来一致：请求前余额 `<= 0` 拒绝，`> 0` 放行，扣减后允许变负数。

### 6.4 `BalanceDeductionService` 改造

方法签名从按 `enterpriseId` 扣减改为按 `accountId + accountType` 扣减：

```java
@Transactional
public void deductAndAlert(String accountId, Integer accountType,
                           BigDecimal cost, EnterpriseInfo info)
```

扣减口径保持不变：
1. 无条件扣减 `balance = balance - cost`（允许扣至负数）。
2. 扣减后通过原生查询重读真实余额。
3. 按现有 `shouldAlert` 规则判断下穿阈值线。
4. 原子更新 `last_warn_threshold`。
5. 满足条件时发送低余额预警短信。

### 6.5 `BillingContext` 改造

新增字段：

```java
private String accountId;      // company_id 或 user_id
private Integer accountType;   // 1=企业, 2=个人
```

在 `BaseAdapter` / `StreamingRequestProcessor` 组装 `BillingContext` 时设置：
- `userType = 1`（企业）：`accountId = companyId`，`accountType = 1`
- `userType = 2`（个人）：`accountId = userId`，`accountType = 2`

`BillingService.recordBilling()` 中调用 `EnterpriseLookupService.lookupByAccount(accountId, accountType)` 查询余额/预警配置，再传给 `BalanceDeductionService.deductAndAlert()`。

### 6.6 `PlatformDataSyncService` 改造

认证阶段（`resolveUserLink`）：
- 继续关联 `ai_enterprise` 获取 `enterpriseId` 和 `enterpriseName`（用于折扣白名单和日志）。
- 不再读取 `ai_enterprise.balance`；原日志中打印余额的代码需要移除或改为从 `ai_account_balance` 读取（可选）。

## 7. 数据流

### 7.1 认证阶段

```
X-API-Key → 算力平台认证 → resolveUserLink(platformUserId)
  → sldd_system_users 实名认证校验
  → 关联 ai_enterprise 获取 enterpriseId / enterpriseName / company_id
  → 返回 UserIdentity（含 enterpriseId, companyId, userId, userType 等）
```

余额信息不参与认证，保持实时读取。

### 7.2 请求前余额校验

```
BaseAdapter.processRequest()
  → BalanceCheckService.checkBalance(UserIdentity, serviceType)
    → 非平台用户：跳过
    → 未实名认证：403
    → 免费额度有剩余：放行
    → 确定 accountId / accountType
    → EnterpriseLookupService.lookupByAccount(accountId, accountType)
      → ai_enterprise：企业名称
      → ai_account_balance：余额/预警配置
    → balance < 0：402
    → balance >= 0：放行
```

### 7.3 请求后计费扣减

```
Processor → BillingService.recordBilling(BillingContext)
  → 保存 billing_record
  → 免费额度命中或 cost <= 0：跳过
  → EnterpriseLookupService.lookupByAccount(accountId, accountType)
    → ai_enterprise：企业名称（用于通知）
    → ai_account_balance：余额/预警配置
  → BalanceDeductionService.deductAndAlert(accountId, accountType, cost, info)
    → 查 ai_account_balance.id
    → UPDATE ai_account_balance SET balance = balance - :cost
    → 原生查询重读 balance
    → shouldAlert() 判断下穿阈值线
    → 原子更新 last_warn_threshold
    → 触发 sendLowBalanceAlert()
```

### 7.4 余额回正（充值）

算力平台充值时直接更新 `ai_account_balance.balance`。JAiRouter 不再维护 `arrear_status`，余额回正后下次请求前校验自然放行。

充值后若余额回到 `last_warn_threshold` 上方，再次消费下穿时必须重新触发低余额预警。`updateLastWarnThreshold` 的第三个条件 `:balanceBeforeDeduction > b.lastWarnThreshold` 专门兜住该场景，避免充值后持续被旧的 `last_warn_threshold` 压制。

## 8. 边界情况

1. **`ai_account_balance` 记录不存在**：视为余额 `0`，请求前返回 402。
2. **并发扣减**：原子 `UPDATE` + 原生查询重读真实余额，保持原逻辑。
3. **个人用户余额**：和企业用户一样按 `balance <= 0` 拦截，免费额度优先于余额检查。

## 9. 测试策略

### 9.1 改造现有测试

| 测试类 | 改造点 |
|---|---|
| `BalanceCheckServiceTest` | Mock 改为 `PlatformAccountBalanceRepository`；增加个人用户余额检查用例。 |
| `BalanceDeductionServiceTest` | Mock 改为 `PlatformAccountBalanceRepository`；入参改为 `accountId + accountType`。 |
| `BillingServiceTest` | 更新 `BillingContext` 构造，补充 `accountId`/`accountType`；Mock `EnterpriseLookupService.lookupByAccount`。 |

### 9.2 新增测试

- `PlatformAccountBalanceRepositoryTest`（可选）：验证查询、扣减余额、更新 `last_warn_threshold`。
- `EnterpriseLookupServiceTest`：验证从 `ai_enterprise` + `ai_account_balance` 组装 `EnterpriseInfo`。

### 9.3 集成验证

- 企业用户完整链路：余额校验 → 响应完成 → 异步扣减 → 触发预警短信。
- 个人用户链路：免费额度耗尽 → 检查余额 → 余额不足返回 402。

## 10. 上线与迁移注意事项

1. **数据库迁移**：算力平台负责创建 `ai_account_balance` 表并迁移历史余额/预警数据。
2. **字段清理**：`ai_enterprise` 中 `balance`、`warn_enabled`、`warn_threshold`、`last_warn_time`、`last_warn_threshold`、`arrear_status` 可在 JAiRouter 上线稳定后清理。
3. **缓存策略**：余额/预警信息实时读取，不走缓存；`PlatformAuthCache` 中缓存的 `UserIdentity` 仍按原策略失效（`/internal/refresh` 或 TTL）。
4. **兼容性**：上线期间建议保留 `ai_enterprise` 旧字段双写或至少不立即删除，确保可回滚。
