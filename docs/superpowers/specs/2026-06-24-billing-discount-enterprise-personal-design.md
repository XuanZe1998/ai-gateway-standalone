# 计费账单企业/个人折扣与企业白名单折上折设计

## 背景

算力平台账单管理需要区分企业用户和个人用户，并在现有模型折扣基础上新增两个折扣维度：

1. **用户类型冗余到账单**：将 `sldd_system_users.user_type` 冗余写入 `ai_billing_record`。
2. **用户折扣管理**：企业和个人用户可在 `ai_user_discount` 独立设置折扣。
3. **企业白名单补贴折扣**：企业用户在 `ai_enterprise.subsidy_discount` 设置政府补贴折扣，实现折上折。

> 示例：模型折扣 9 折 + 企业用户折扣 9 折 + 白名单补贴 8 折 → 100 元算力账单金额 = 100 × 0.9 × 0.9 × 0.8 = 72 元。

## 设计目标

- 保持现有模型折扣逻辑不变。
- 实时读取折扣（不加缓存）。
- 各级折扣率作为快照写入账单，便于审计对账。
- 余额扣减基于折后金额；请求前余额校验保持粗略（仅 `balance < 0` 拒绝）。

## 关键决策

- **折扣计算位置**：抽取独立的 `DiscountCalculationService`，职责单一，便于扩展和测试。
- **折扣应用方式**：在总价层面折上折，保留现有 `originalCost / discountAmount / totalCost` 结构。
- **用户类型传播**：认证时从 `sldd_system_users` 读取 `user_type`，透传到 `UserIdentity` 和 `BillingContext`。
- **历史数据兼容**：保留 `billing_record.discount_rate` 作为模型折扣，新增 `user_discount_rate`、`enterprise_discount_rate`、`final_discount_rate`、`user_type` 字段，无需迁移历史数据。

## 数据模型变更

### `PlatformSystemUserEntity`

新增字段映射 `sldd_system_users.user_type`：

```java
@Column(name = "user_type")
private Integer userType;  // 1=企业用户，2=个人用户
```

### `PlatformEnterpriseEntity`

新增字段映射 `ai_enterprise.subsidy_discount`：

```java
@Column(name = "subsidy_discount")
private Integer subsidyDiscount;  // 95=95折，null=无折扣
```

### 新增 `UserDiscountEntity`

映射算力平台 `ai_user_discount` 表：

```java
@Entity
@Table(name = "ai_user_discount")
public class UserDiscountEntity {
    @Id
    private Long id;

    @Column(name = "user_type")
    private Integer userType;  // 1=企业用户，2=个人用户

    @Column(name = "user_id")
    private String userId;     // 企业=companyId，个人=sldd_system_users.id

    @Column(name = "discount")
    private Integer discount;  // 95=95折，null=无折扣
}
```

对应新增 `UserDiscountRepository`，按 `(userType, userId)` 查询。

### `BillingRecordEntity`

新增快照字段：

```java
private Integer userType;                  // 冗余：1=企业，2=个人
private BigDecimal userDiscountRate;       // 用户折扣率（0.90）
private BigDecimal enterpriseDiscountRate; // 企业补贴折扣率（0.80）
private BigDecimal finalDiscountRate;      // 最终折扣率 = 模型折扣 × 用户折扣 × 企业补贴折扣
```

保留原有 `discountRate` 字段作为模型折扣率。

## 用户身份与计费上下文传播

### `UserIdentity` 扩展

```java
public record UserIdentity(
        String userId,
        String userAccount,
        String apiKeyId,
        String apiKeyName,
        Long enterpriseId,
        String enterpriseName,
        String companyId,
        boolean platformUser,
        Integer userType,           // 新增
        Long systemUserId           // 新增：sldd_system_users.id（PK）
) { }
```

### `EnterpriseLink` 扩展

`PlatformDataSyncService` 内部 record 同步扩展：

```java
private record EnterpriseLink(
    Long enterpriseId,
    String enterpriseName,
    String companyId,
    String systemUserId,
    Integer userType
) {}
```

在 `resolveEnterpriseLink()` 中从 `PlatformSystemUserEntity` 读取 `userType` 并携带。

### `BillingContext` 扩展

新增字段：

```java
private final Integer userType;
private final Long systemUserId;
private final String companyId;
```

Builder/静态工厂方法同步扩展。

## 折扣计算服务

### `DiscountCalculationService`

```java
@Service
@RequiredArgsConstructor
public class DiscountCalculationService {

    private final ModelPricingService modelPricingService;
    private final UserDiscountRepository userDiscountRepository;
    private final PlatformEnterpriseRepository enterpriseRepository;

    public DiscountBreakdown calculate(UserIdentity identity, String modelName, String channelId) {
        BigDecimal modelRate = resolveModelDiscountRate(modelName, channelId);
        BigDecimal userRate = resolveUserDiscountRate(identity);
        BigDecimal enterpriseRate = resolveEnterpriseDiscountRate(identity);

        BigDecimal finalRate = modelRate.multiply(userRate)
                .setScale(6, RoundingMode.HALF_UP)
                .multiply(enterpriseRate)
                .setScale(6, RoundingMode.HALF_UP);

        return new DiscountBreakdown(modelRate, userRate, enterpriseRate, finalRate);
    }

    // ... 私有解析方法
}
```

### `DiscountBreakdown`

```java
public record DiscountBreakdown(
    BigDecimal modelDiscountRate,
    BigDecimal userDiscountRate,
    BigDecimal enterpriseDiscountRate,
    BigDecimal finalDiscountRate
) {}
```

### 折扣解析规则

**模型折扣**：
- 从 `ModelPricingService.getPrice(modelName, channelId)` 获取 `ModelPricing.discountRate`。
- 保持现有逻辑不变。

**用户折扣**：
- 非平台用户（本地 API Key / JWT）→ `1.0`
- 企业用户（`userType == 1`）：按 `(userType=1, userId=companyId)` 查询 `ai_user_discount`
- 个人用户（`userType == 2`）：按 `(userType=2, userId=systemUserId)` 查询 `ai_user_discount`
- 无记录或 `discount == null` → `1.0`
- 查到 `discount=90` → 转换为 `0.90`

**企业补贴折扣**：
- 非企业用户 → `1.0`
- 企业用户：按 `enterpriseId` 查询 `ai_enterprise.subsidy_discount`
  - `null` → `1.0`
  - `95` → `0.95`

### 折上折效果

| 用户类型 | 折扣组合 |
|---------|---------|
| 个人用户 | 模型折扣 × 个人用户折扣 |
| 企业用户 | 模型折扣 × 企业用户折扣 × 企业补贴折扣 |

## `BillingService` 计费流程变更

### `buildRecord()` 调整

```java
private BillingRecordEntity buildRecord(BillingContext ctx) {
    // 1. 原始费用
    BigDecimal originalCost = new BigDecimal(promptTokens).multiply(inputPrice)
            .add(new BigDecimal(completionTokens).multiply(outputPrice));

    // 2. 组装用户身份并查询多级折扣
    UserIdentity identity = new UserIdentity(
            ctx.getUserId(), ctx.getUserAccount(),
            ctx.getApiKeyId(), ctx.getApiKeyName(),
            ctx.getEnterpriseId(), ctx.getEnterpriseName(), ctx.getCompanyId(),
            ctx.getEnterpriseId() != null,
            ctx.getUserType(), ctx.getSystemUserId());
    DiscountBreakdown discounts = discountCalculationService.calculate(
            identity, ctx.getModelName(), ctx.getChannelId());

    // 3. 用最终折扣率计算费用
    BigDecimal discountAmount = originalCost
            .multiply(BigDecimal.ONE.subtract(discounts.finalDiscountRate()))
            .setScale(6, RoundingMode.HALF_UP);
    BigDecimal totalCost = originalCost.subtract(discountAmount);

    // 4. 免费额度覆盖（保持原逻辑）
    if (Boolean.TRUE.equals(ctx.getIsFreeQuota())) {
        originalCost = BigDecimal.ZERO;
        discountAmount = BigDecimal.ZERO;
        totalCost = BigDecimal.ZERO;
    }

    // 5. 构建记录
    return BillingRecordEntity.builder()
            .discountRate(discounts.modelDiscountRate())
            .userDiscountRate(discounts.userDiscountRate())
            .enterpriseDiscountRate(discounts.enterpriseDiscountRate())
            .finalDiscountRate(discounts.finalDiscountRate())
            .userType(ctx.getUserType())
            .originalCost(originalCost)
            .discountAmount(discountAmount)
            .totalCost(totalCost)
            // ... 其他字段
            .build();
}
```

### 余额扣减

`recordBilling()` 后续调用：

```java
balanceDeductionService.deductAndAlert(enterpriseId, totalCost, ...);
```

扣减金额为折后金额。

### 余额校验

`BalanceCheckService` 保持粗略校验：仅当 `balance < 0` 时返回 402，不预估折后费用。

## 错误处理

- 查询 `ai_user_discount` 或 `ai_enterprise` 失败时，对应维度折扣降级为 `1.0`，记录 warning 日志，不阻塞计费。
- `userType` 为空或不为 `1/2` 时，按无用户折扣处理。
- 折扣百分比转换统一除以 100（如 `90 → 0.90`）。
- 任何折扣率 `< 0` 时按 `1.0` 处理。
- 每级折扣相乘后使用 `HALF_UP` 保留 6 位小数。

## 测试策略

- **单元测试 `DiscountCalculationServiceTest`**：
  - 个人用户：模型折扣 0.9 × 个人折扣 0.85 = 最终 0.765
  - 企业用户：模型折扣 0.9 × 企业用户折扣 0.85 × 补贴折扣 0.8 = 最终 0.612
  - 无折扣记录：最终等于模型折扣
  - DB 查询失败：降级为无折扣
- **集成测试 `BillingServiceTest` 补充**：验证账单快照字段 `userType`、`userDiscountRate`、`enterpriseDiscountRate`、`finalDiscountRate` 正确写入。
- 原有模型折扣测试保持不变。

## DDL 汇总

以下字段/表用户已确认在算力平台侧已就绪，此处仅做汇总：

```sql
ALTER TABLE sldd_system_users ADD COLUMN IF NOT EXISTS user_type INT;
ALTER TABLE ai_enterprise ADD COLUMN IF NOT EXISTS subsidy_discount INT;

-- ai_user_discount 表已存在，结构如下（参考算力平台）：
-- id, user_type, user_id, discount, create_time, update_time, ...
```

## 影响范围

| 文件 | 变更 |
|------|------|
| `persistence/jpa/entity/platform/PlatformSystemUserEntity.java` | 新增 `userType` 字段 |
| `persistence/jpa/entity/platform/PlatformEnterpriseEntity.java` | 新增 `subsidyDiscount` 字段 |
| `persistence/jpa/entity/billing/UserDiscountEntity.java` | 新增 |
| `persistence/jpa/repository/billing/UserDiscountRepository.java` | 新增 |
| `persistence/jpa/entity/BillingRecordEntity.java` | 新增折扣快照字段和 `userType` |
| `auth/security/model/UserIdentity.java` | 新增 `userType`、`systemUserId` |
| `platform/sync/PlatformDataSyncService.java` | `EnterpriseLink` 扩展，认证时读取 `userType` |
| `billing/BillingContext.java` | 新增 `userType`、`systemUserId`、`companyId` |
| `billing/DiscountCalculationService.java` | 新增 |
| `billing/DiscountBreakdown.java` | 新增 |
| `billing/BillingService.java` | `buildRecord()` 调用折扣服务并写入快照 |
| `billing/BillingServiceTest.java` | 补充测试 |
| `billing/DiscountCalculationServiceTest.java` | 新增测试 |
