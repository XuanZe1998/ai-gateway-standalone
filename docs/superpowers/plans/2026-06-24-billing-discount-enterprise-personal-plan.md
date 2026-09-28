# 计费账单企业/个人折扣与企业白名单折上折 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有计费链路中区分企业/个人用户，新增用户折扣与企业白名单补贴折扣两个维度，实现折上折，并将各级折扣率与用户类型冗余到账单表。

**Architecture:** 保持模型折扣不变，抽取独立的 `DiscountCalculationService` 负责实时查询并计算多级折扣率；`BillingService` 调用该服务并把折扣快照写入 `BillingRecordEntity`；`UserIdentity` 与 `BillingContext` 扩展以透传 `userType` 与 `systemUserId`。

**Tech Stack:** Java 17, Spring Boot 3.5.5, Spring Data JPA, Maven, JUnit 5, Mockito

## Global Constraints

- 保持现有模型折扣逻辑不变。
- 实时读取折扣，不加缓存。
- 各级折扣率作为快照写入账单，便于审计对账。
- 余额扣减基于折后金额；请求前余额校验保持粗略（仅 `balance < 0` 拒绝）。
- 折扣百分比转小数：90 → 0.90；null → 1.0。
- 每级折扣相乘后使用 `RoundingMode.HALF_UP` 保留 6 位小数。
- 负折扣保护：折扣率 `< 0` 时按 1.0 处理。
- 折上折规则：个人用户 = 模型折扣 × 用户折扣；企业用户 = 模型折扣 × 用户折扣 × 企业补贴折扣。
- 编译命令：`JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true`
- 测试命令：`JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest={TestClass} -Dcheckstyle.skip=true -Dspotbugs.skip=true`

## File Structure

| 文件 | 责任 |
|------|------|
| `persistence/jpa/entity/platform/PlatformSystemUserEntity.java` | 新增 `userType` 字段映射 `sldd_system_users.user_type` |
| `persistence/jpa/entity/platform/PlatformEnterpriseEntity.java` | 新增 `subsidyDiscount` 字段映射 `ai_enterprise.subsidy_discount` |
| `persistence/jpa/entity/billing/UserDiscountEntity.java` | 新增，映射 `ai_user_discount` |
| `persistence/jpa/repository/billing/UserDiscountRepository.java` | 新增，按 `(userType, userId)` 查询 |
| `persistence/jpa/entity/BillingRecordEntity.java` | 新增 `userType`、`userDiscountRate`、`enterpriseDiscountRate`、`finalDiscountRate` |
| `auth/security/model/UserIdentity.java` | 新增 `userType`、`systemUserId` |
| `platform/sync/PlatformDataSyncService.java` | 扩展 `EnterpriseLink`，认证时读取并携带 `userType`；构造 `UserIdentity` 时填充新字段 |
| `billing/BillingService.java` | 扩展 `BillingContext`；`buildRecord()` 调用 `DiscountCalculationService` 并写入折扣快照 |
| `billing/DiscountBreakdown.java` | 新增，多级折扣率聚合 record |
| `billing/DiscountCalculationService.java` | 新增，核心折扣计算服务 |
| `test/java/org/unreal/modelrouter/billing/DiscountCalculationServiceTest.java` | 新增单元测试 |
| `test/java/org/unreal/modelrouter/billing/BillingServiceTest.java` | 修改/补充，验证折扣快照写入 |

---

## Task 1: 数据模型层 — 用户类型、企业补贴折扣、用户折扣表、账单快照字段

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformSystemUserEntity.java`
- Modify: `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformEnterpriseEntity.java`
- Create: `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/billing/UserDiscountEntity.java`
- Create: `src/main/java/org/unreal/modelrouter/persistence/jpa/repository/billing/UserDiscountRepository.java`
- Modify: `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/BillingRecordEntity.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `PlatformSystemUserEntity.getUserType(): Integer`
  - `PlatformEnterpriseEntity.getSubsidyDiscount(): Integer`
  - `UserDiscountRepository.findByUserTypeAndUserId(Integer userType, String userId): Optional<UserDiscountEntity>`
  - `BillingRecordEntity` 新增字段的 getter/setter/builder 方法

### Step 1.1: 修改 `PlatformSystemUserEntity`

在现有字段后新增：

```java
@Column(name = "user_type")
private Integer userType;
```

并添加 getter/setter（如果类使用 Lombok `@Data`，则无需手动添加）。

验证：
```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS

### Step 1.2: 修改 `PlatformEnterpriseEntity`

在现有字段后新增：

```java
@Column(name = "subsidy_discount")
private Integer subsidyDiscount;
```

验证同上。

### Step 1.3: 创建 `UserDiscountEntity`

```java
package org.unreal.modelrouter.persistence.jpa.entity.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 映射算力平台 ai_user_discount 表（只读）。
 */
@Data
@Entity
@Table(name = "ai_user_discount")
public class UserDiscountEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "user_type")
    private Integer userType;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "discount")
    private Integer discount;
}
```

### Step 1.4: 创建 `UserDiscountRepository`

```java
package org.unreal.modelrouter.persistence.jpa.repository.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.billing.UserDiscountEntity;

import java.util.Optional;

@Repository
public interface UserDiscountRepository extends JpaRepository<UserDiscountEntity, Long> {

    Optional<UserDiscountEntity> findByUserTypeAndUserId(Integer userType, String userId);
}
```

### Step 1.5: 修改 `BillingRecordEntity`

在现有字段后新增：

```java
@Column(name = "user_type")
private Integer userType;

@Column(name = "user_discount_rate", precision = 10, scale = 6)
private BigDecimal userDiscountRate;

@Column(name = "enterprise_discount_rate", precision = 10, scale = 6)
private BigDecimal enterpriseDiscountRate;

@Column(name = "final_discount_rate", precision = 10, scale = 6)
private BigDecimal finalDiscountRate;
```

如果使用 Lombok `@Builder`，确保 builder 自动包含新字段；否则手动添加 builder 方法。

### Step 1.6: 编译并提交

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS

```bash
git add src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformSystemUserEntity.java \
       src/main/java/org/unreal/modelrouter/persistence/jpa/entity/platform/PlatformEnterpriseEntity.java \
       src/main/java/org/unreal/modelrouter/persistence/jpa/entity/billing/UserDiscountEntity.java \
       src/main/java/org/unreal/modelrouter/persistence/jpa/repository/billing/UserDiscountRepository.java \
       src/main/java/org/unreal/modelrouter/persistence/jpa/entity/BillingRecordEntity.java
git commit -m "feat(billing): 数据模型层支持用户类型、用户折扣、企业补贴折扣快照"
```

---

## Task 2: 用户身份传播 — UserIdentity 与 PlatformDataSyncService

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/auth/security/model/UserIdentity.java`
- Modify: `src/main/java/org/unreal/modelrouter/platform/sync/PlatformDataSyncService.java`

**Interfaces:**
- Consumes: `PlatformSystemUserEntity.getUserType()`
- Produces:
  - `UserIdentity.userType(): Integer`
  - `UserIdentity.systemUserId(): Long`
  - `PlatformDataSyncService` 认证链路构造的 `UserIdentity` 携带 `userType` 和 `systemUserId`

### Step 2.1: 修改 `UserIdentity`

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
        Integer userType,
        Long systemUserId
) {
    public static final UserIdentity SYSTEM = new UserIdentity(
            "system", "system", null, null, null, null, null, false, null, null);

    public static final String CONTEXT_KEY = "user.identity";
}
```

注意：`SYSTEM` 静态常量必须同步更新构造参数。

### Step 2.2: 修改 `PlatformDataSyncService.EnterpriseLink`

找到内部 record：

```java
private record EnterpriseLink(Long enterpriseId, String enterpriseName, String companyId, String systemUserId) {}
```

改为：

```java
private record EnterpriseLink(Long enterpriseId, String enterpriseName, String companyId, String systemUserId, Integer userType) {}
```

### Step 2.3: 修改 `resolveEnterpriseLink` 返回值

找到：

```java
return new EnterpriseLink(ent.getId(), enterpriseName, ent.getCompanyId(), user.getUserId());
```

改为：

```java
return new EnterpriseLink(ent.getId(), enterpriseName, ent.getCompanyId(), user.getUserId(), user.getUserType());
```

### Step 2.4: 修改 `UserIdentity` 构造处

找到：

```java
String userId = link.systemUserId() != null ? link.systemUserId() : entity.getUserAccount();
return new ApiKeyLookupResult(new UserIdentity(
        userId, entity.getUserAccount(),
        String.valueOf(entity.getId()),
        entity.getDescription(),
        link.enterpriseId(),
        link.enterpriseName(),
        link.companyId(),
        true
), false, entity.getExpireTime());
```

改为：

```java
String userId = link.systemUserId() != null ? link.systemUserId() : entity.getUserAccount();
return new ApiKeyLookupResult(new UserIdentity(
        userId, entity.getUserAccount(),
        String.valueOf(entity.getId()),
        entity.getDescription(),
        link.enterpriseId(),
        link.enterpriseName(),
        link.companyId(),
        true,
        link.userType(),
        entity.getUserId()
), false, entity.getExpireTime());
```

注意：`entity.getUserId()` 是 `ai_api_key.user_id`，即 `sldd_system_users.id`（PK）。

### Step 2.5: 编译并提交

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS

```bash
git add src/main/java/org/unreal/modelrouter/auth/security/model/UserIdentity.java \
       src/main/java/org/unreal/modelrouter/platform/sync/PlatformDataSyncService.java
git commit -m "feat(auth): UserIdentity 透传 userType 与 systemUserId"
```

---

## Task 3: 计费上下文扩展 — BillingContext

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/billing/BillingService.java`（内部类 `BillingContext`）

**Interfaces:**
- Consumes: 无
- Produces:
  - `BillingContext.userType(Integer): BillingContext`
  - `BillingContext.systemUserId(Long): BillingContext`
  - `BillingContext.getUserType(): Integer`
  - `BillingContext.getSystemUserId(): Long`

### Step 3.1: 在 `BillingContext` 中新增字段与方法

在 `private String companyId;` 后新增：

```java
private Integer userType;
private Long systemUserId;
```

在 builder 方法区新增：

```java
public BillingContext userType(Integer v) { this.userType = v; return this; }
public BillingContext systemUserId(Long v) { this.systemUserId = v; return this; }
```

在 getter 区新增：

```java
public Integer getUserType() { return userType; }
public Long getSystemUserId() { return systemUserId; }
```

### Step 3.2: 编译并提交

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS

```bash
git add src/main/java/org/unreal/modelrouter/billing/BillingService.java
git commit -m "feat(billing): BillingContext 扩展 userType 与 systemUserId"
```

---

## Task 4: 折扣计算服务 — DiscountBreakdown 与 DiscountCalculationService

**Files:**
- Create: `src/main/java/org/unreal/modelrouter/billing/DiscountBreakdown.java`
- Create: `src/main/java/org/unreal/modelrouter/billing/DiscountCalculationService.java`
- Create: `src/test/java/org/unreal/modelrouter/billing/DiscountCalculationServiceTest.java`

**Interfaces:**
- Consumes:
  - `UserIdentity.userType(): Integer`
  - `UserIdentity.systemUserId(): Long`
  - `UserIdentity.companyId(): String`
  - `UserIdentity.enterpriseId(): Long`
  - `UserIdentity.platformUser(): boolean`
  - `ModelPricingService.getPrice(String, String): ModelPricing`
  - `UserDiscountRepository.findByUserTypeAndUserId(Integer, String): Optional<UserDiscountEntity>`
  - `PlatformEnterpriseRepository.findById(Long): Optional<PlatformEnterpriseEntity>`
- Produces:
  - `DiscountCalculationService.calculate(UserIdentity, String, String): DiscountBreakdown`

### Step 4.1: 创建 `DiscountBreakdown`

```java
package org.unreal.modelrouter.billing;

import java.math.BigDecimal;

/**
 * 多级折扣率聚合结果。
 */
public record DiscountBreakdown(
        BigDecimal modelDiscountRate,
        BigDecimal userDiscountRate,
        BigDecimal enterpriseDiscountRate,
        BigDecimal finalDiscountRate
) {
}
```

### Step 4.2: 创建 `DiscountCalculationService`

```java
package org.unreal.modelrouter.billing;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.persistence.jpa.entity.billing.UserDiscountEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformEnterpriseEntity;
import org.unreal.modelrouter.persistence.jpa.repository.billing.UserDiscountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformEnterpriseRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DiscountCalculationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscountCalculationService.class);
    private static final BigDecimal NO_DISCOUNT = BigDecimal.ONE;
    private static final int SCALE = 6;

    private final ModelPricingService modelPricingService;
    private final UserDiscountRepository userDiscountRepository;
    private final PlatformEnterpriseRepository enterpriseRepository;

    public DiscountBreakdown calculate(UserIdentity identity, String modelName, String channelId) {
        BigDecimal modelRate = resolveModelDiscountRate(modelName, channelId);
        BigDecimal userRate = resolveUserDiscountRate(identity);
        BigDecimal enterpriseRate = resolveEnterpriseDiscountRate(identity);

        BigDecimal finalRate = modelRate.multiply(userRate)
                .setScale(SCALE, RoundingMode.HALF_UP)
                .multiply(enterpriseRate)
                .setScale(SCALE, RoundingMode.HALF_UP);

        return new DiscountBreakdown(modelRate, userRate, enterpriseRate, finalRate);
    }

    private BigDecimal resolveModelDiscountRate(String modelName, String channelId) {
        try {
            ModelPricingService.ModelPricing pricing = modelPricingService.getPrice(modelName, channelId);
            if (pricing == null || pricing.getDiscountRate() == null) {
                return NO_DISCOUNT;
            }
            return normalizeRate(pricing.getDiscountRate());
        } catch (Exception e) {
            LOGGER.warn("查询模型折扣失败, modelName={}, channelId={}: {}", modelName, channelId, e.getMessage());
            return NO_DISCOUNT;
        }
    }

    private BigDecimal resolveUserDiscountRate(UserIdentity identity) {
        if (identity == null || !identity.platformUser()) {
            return NO_DISCOUNT;
        }
        Integer userType = identity.userType();
        if (userType == null) {
            return NO_DISCOUNT;
        }
        String userId;
        if (userType == 1) {
            userId = identity.companyId();
        } else if (userType == 2) {
            userId = identity.systemUserId() != null ? String.valueOf(identity.systemUserId()) : null;
        } else {
            LOGGER.warn("未知用户类型, 按无折扣处理: userType={}", userType);
            return NO_DISCOUNT;
        }
        if (userId == null || userId.isBlank()) {
            return NO_DISCOUNT;
        }
        try {
            Optional<UserDiscountEntity> opt = userDiscountRepository.findByUserTypeAndUserId(userType, userId);
            if (opt.isEmpty() || opt.get().getDiscount() == null) {
                return NO_DISCOUNT;
            }
            return percentageToRate(opt.get().getDiscount());
        } catch (Exception e) {
            LOGGER.warn("查询用户折扣失败, userType={}, userId={}: {}", userType, userId, e.getMessage());
            return NO_DISCOUNT;
        }
    }

    private BigDecimal resolveEnterpriseDiscountRate(UserIdentity identity) {
        if (identity == null || !identity.platformUser()) {
            return NO_DISCOUNT;
        }
        if (!Integer.valueOf(1).equals(identity.userType()) || identity.enterpriseId() == null) {
            return NO_DISCOUNT;
        }
        try {
            Optional<PlatformEnterpriseEntity> opt = enterpriseRepository.findById(identity.enterpriseId());
            if (opt.isEmpty() || opt.get().getSubsidyDiscount() == null) {
                return NO_DISCOUNT;
            }
            return percentageToRate(opt.get().getSubsidyDiscount());
        } catch (Exception e) {
            LOGGER.warn("查询企业补贴折扣失败, enterpriseId={}: {}", identity.enterpriseId(), e.getMessage());
            return NO_DISCOUNT;
        }
    }

    private BigDecimal percentageToRate(Integer percentage) {
        if (percentage == null) {
            return NO_DISCOUNT;
        }
        return new BigDecimal(percentage).divide(new BigDecimal("100"), SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0) {
            return NO_DISCOUNT;
        }
        return rate.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
```

### Step 4.3: 编写 `DiscountCalculationServiceTest`

```java
package org.unreal.modelrouter.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.persistence.jpa.entity.billing.UserDiscountEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformEnterpriseEntity;
import org.unreal.modelrouter.persistence.jpa.repository.billing.UserDiscountRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformEnterpriseRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscountCalculationServiceTest {

    @Mock
    private ModelPricingService modelPricingService;

    @Mock
    private UserDiscountRepository userDiscountRepository;

    @Mock
    private PlatformEnterpriseRepository enterpriseRepository;

    private DiscountCalculationService service;

    @BeforeEach
    void setUp() {
        service = new DiscountCalculationService(modelPricingService, userDiscountRepository, enterpriseRepository);
    }

    @Test
    void personalUser_withModelAndUserDiscount() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", null, null, null, true, 2, 100L);

        when(modelPricingService.getPrice("gpt-4", "ch1"))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", "ch1", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90")));
        UserDiscountEntity discount = new UserDiscountEntity();
        discount.setUserType(2);
        discount.setUserId("100");
        discount.setDiscount(85);
        when(userDiscountRepository.findByUserTypeAndUserId(2, "100")).thenReturn(Optional.of(discount));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", "ch1");

        assertThat(result.modelDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.userDiscountRate()).isEqualByComparingTo(new BigDecimal("0.850000"));
        assertThat(result.enterpriseDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.765000"));
    }

    @Test
    void enterpriseUser_withAllDiscounts() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", 10L, "ent", "C001", true, 1, 100L);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90")));
        UserDiscountEntity discount = new UserDiscountEntity();
        discount.setUserType(1);
        discount.setUserId("C001");
        discount.setDiscount(90);
        when(userDiscountRepository.findByUserTypeAndUserId(1, "C001")).thenReturn(Optional.of(discount));
        PlatformEnterpriseEntity enterprise = new PlatformEnterpriseEntity();
        enterprise.setId(10L);
        enterprise.setSubsidyDiscount(80);
        when(enterpriseRepository.findById(10L)).thenReturn(Optional.of(enterprise));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.modelDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.userDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.enterpriseDiscountRate()).isEqualByComparingTo(new BigDecimal("0.800000"));
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.648000"));
    }

    @Test
    void localUser_noDiscount() {
        UserIdentity identity = new UserIdentity(
                "local", "local", null, null, null, null, null, false, null, null);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90")));

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.modelDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        assertThat(result.userDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.enterpriseDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
        verifyNoInteractions(userDiscountRepository, enterpriseRepository);
    }

    @Test
    void missingUserDiscount_defaultsToNoDiscount() {
        UserIdentity identity = new UserIdentity(
                "u1", "u1", "k1", "key", null, null, null, true, 2, 100L);

        when(modelPricingService.getPrice("gpt-4", null))
                .thenReturn(new ModelPricingService.ModelPricing("gpt-4", null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.90")));
        when(userDiscountRepository.findByUserTypeAndUserId(2, "100")).thenReturn(Optional.empty());

        DiscountBreakdown result = service.calculate(identity, "gpt-4", null);

        assertThat(result.userDiscountRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.finalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.900000"));
    }
}
```

### Step 4.4: 运行测试

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=DiscountCalculationServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS, tests PASS

### Step 4.5: 提交

```bash
git add src/main/java/org/unreal/modelrouter/billing/DiscountBreakdown.java \
       src/main/java/org/unreal/modelrouter/billing/DiscountCalculationService.java \
       src/test/java/org/unreal/modelrouter/billing/DiscountCalculationServiceTest.java
git commit -m "feat(billing): 折扣计算服务与用户/企业折扣查询"
```

---

## Task 5: BillingService 接入折扣计算并写入快照

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/billing/BillingService.java`

**Interfaces:**
- Consumes:
  - `DiscountCalculationService.calculate(UserIdentity, String, String): DiscountBreakdown`
  - `BillingContext.getUserType(): Integer`
  - `BillingContext.getSystemUserId(): Long`
  - `BillingContext.getCompanyId(): String`
  - `BillingContext.getEnterpriseId(): Long`
- Produces:
  - `BillingRecordEntity` 包含 `userType`、`userDiscountRate`、`enterpriseDiscountRate`、`finalDiscountRate`

### Step 5.1: 注入 `DiscountCalculationService`

修改构造函数：

```java
private final DiscountCalculationService discountCalculationService;

public BillingService(ModelPricingService pricingService,
                      BillingRecordRepository billingRepository,
                      BalanceDeductionService balanceDeductionService,
                      EnterpriseLookupService enterpriseLookupService,
                      org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository systemUserRepository,
                      DiscountCalculationService discountCalculationService) {
    this.pricingService = pricingService;
    this.billingRepository = billingRepository;
    this.balanceDeductionService = balanceDeductionService;
    this.enterpriseLookupService = enterpriseLookupService;
    this.systemUserRepository = systemUserRepository;
    this.discountCalculationService = discountCalculationService;
}
```

### Step 5.2: 修改 `buildRecord()` 中的折扣计算

找到原有折扣计算逻辑（大致如下）：

```java
BigDecimal originalCost = new BigDecimal(promptTokens).multiply(inputPrice)
        .add(new BigDecimal(completionTokens).multiply(outputPrice));

if (discountRate.compareTo(BigDecimal.ZERO) < 0) {
    discountRate = BigDecimal.ONE;
}

BigDecimal discountAmount = originalCost.multiply(BigDecimal.ONE.subtract(discountRate));
BigDecimal totalCost = originalCost.subtract(discountAmount);
```

替换为：

```java
BigDecimal originalCost = new BigDecimal(promptTokens).multiply(inputPrice)
        .add(new BigDecimal(completionTokens).multiply(outputPrice));

// 多级折扣计算
UserIdentity identity = new UserIdentity(
        ctx.getUserId(), ctx.getUserAccount(),
        ctx.getApiKeyId(), ctx.getApiKeyName(),
        ctx.getEnterpriseId(), ctx.getEnterpriseName(), ctx.getCompanyId(),
        ctx.getEnterpriseId() != null,
        ctx.getUserType(), ctx.getSystemUserId());
DiscountBreakdown discounts = discountCalculationService.calculate(
        identity, ctx.getModelName(), ctx.getChannelId());

BigDecimal discountAmount = originalCost
        .multiply(BigDecimal.ONE.subtract(discounts.finalDiscountRate()))
        .setScale(6, RoundingMode.HALF_UP);
BigDecimal totalCost = originalCost.subtract(discountAmount);
```

### Step 5.3: 修改 `BillingRecordEntity` builder 设置折扣快照

找到 builder 中设置 `discountRate` 的地方，确保如下设置：

```java
.discountRate(discounts.modelDiscountRate())
.userDiscountRate(discounts.userDiscountRate())
.enterpriseDiscountRate(discounts.enterpriseDiscountRate())
.finalDiscountRate(discounts.finalDiscountRate())
.userType(ctx.getUserType())
```

注意：原字段 `discountRate` 现在存储的是模型折扣率（`discounts.modelDiscountRate()`），语义上与之前一致。

### Step 5.4: 免费额度覆盖逻辑保持不变

确保免费额度分支仍强制清零：

```java
if (Boolean.TRUE.equals(ctx.getIsFreeQuota())) {
    originalCost = BigDecimal.ZERO;
    discountAmount = BigDecimal.ZERO;
    totalCost = BigDecimal.ZERO;
}
```

### Step 5.5: 编译并运行现有 `BillingServiceTest`

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=BillingServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS。如果测试因构造参数变化失败，同步更新测试中的 `BillingService` 构造。

### Step 5.6: 提交

```bash
git add src/main/java/org/unreal/modelrouter/billing/BillingService.java
git commit -m "feat(billing): BillingService 接入多级折扣并写入账单快照"
```

---

## Task 6: 适配 BaseAdapter 构建 BillingContext

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/router/adapter/BaseAdapter.java`
- 可能修改所有 `adapter/impl/*Adapter.java`（如果它们直接构造 `BillingContext`）

**Interfaces:**
- Consumes: `UserIdentity.userType()`、`UserIdentity.systemUserId()`、`UserIdentity.companyId()`
- Produces: `BillingContext` 携带 `userType` 和 `systemUserId`

### Step 6.1: 找到 BillingContext 构造处

在 `BaseAdapter` 中搜索 `BillingContext.create()`，通常类似：

```java
BillingContext ctx = BillingContext.create()
        .userId(identity.userId())
        .userAccount(identity.userAccount())
        ...
        .companyId(identity.companyId())
        .enterpriseId(identity.enterpriseId())
        ...
```

### Step 6.2: 补充 userType 与 systemUserId

在链式调用中补充：

```java
.companyId(identity.companyId())
.userType(identity.userType())
.systemUserId(identity.systemUserId())
.enterpriseId(identity.enterpriseId())
```

### Step 6.3: 编译并运行相关测试

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=BillingServiceTest,BaseAdapterExtendedTest,BaseAdapterFreeQuotaTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS

### Step 6.4: 提交

```bash
git add src/main/java/org/unreal/modelrouter/router/adapter/BaseAdapter.java \
       src/main/java/org/unreal/modelrouter/router/adapter/impl/*.java
git commit -m "feat(adapter): BillingContext 构建时透传 userType 与 systemUserId"
```

---

## Task 7: 补充 BillingServiceTest 验证折扣快照

**Files:**
- Modify: `src/test/java/org/unreal/modelrouter/billing/BillingServiceTest.java`

**Interfaces:**
- Consumes: `BillingService` 注入 `DiscountCalculationService` 后的行为
- Produces: 测试用例验证 `BillingRecordEntity` 的新字段

### Step 7.1: 更新测试中的 `BillingService` 构造

如果测试手动构造 `BillingService`，需要补充 `DiscountCalculationService` mock：

```java
@Mock
private DiscountCalculationService discountCalculationService;

@BeforeEach
void setUp() {
    billingService = new BillingService(
            pricingService, billingRepository, balanceDeductionService,
            enterpriseLookupService, systemUserRepository, discountCalculationService);
}
```

### Step 7.2: 编写折扣快照验证测试

```java
@Test
void shouldWriteDiscountSnapshotForEnterpriseUser() {
    BillingContext ctx = BillingContext.create()
            .userId("u1")
            .userAccount("u1")
            .modelName("gpt-4")
            .serviceType("chat")
            .channelId("ch1")
            .promptTokens(1000L)
            .completionTokens(500L)
            .totalTokens(1500L)
            .enterpriseId(10L)
            .enterpriseName("TestEnt")
            .companyId("C001")
            .userType(1)
            .systemUserId(100L)
            .startedAt(LocalDateTime.now());

    when(pricingService.getPrice("gpt-4", "ch1"))
            .thenReturn(new ModelPricingService.ModelPricing("gpt-4", "ch1",
                    new BigDecimal("0.001"), new BigDecimal("0.002"), new BigDecimal("1.00")));
    when(discountCalculationService.calculate(any(UserIdentity.class), eq("gpt-4"), eq("ch1")))
            .thenReturn(new DiscountBreakdown(
                    new BigDecimal("0.90"),
                    new BigDecimal("0.85"),
                    new BigDecimal("0.80"),
                    new BigDecimal("0.612000")));

    billingService.recordBilling(ctx);

    ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
    verify(billingRepository).save(captor.capture());
    BillingRecordEntity record = captor.getValue();

    assertThat(record.getUserType()).isEqualTo(1);
    assertThat(record.getDiscountRate()).isEqualByComparingTo(new BigDecimal("0.90"));
    assertThat(record.getUserDiscountRate()).isEqualByComparingTo(new BigDecimal("0.85"));
    assertThat(record.getEnterpriseDiscountRate()).isEqualByComparingTo(new BigDecimal("0.80"));
    assertThat(record.getFinalDiscountRate()).isEqualByComparingTo(new BigDecimal("0.612000"));
    assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("0.918000"));
}
```

（输入 1000 prompt × 0.001 + 500 completion × 0.002 = 2.0 原始费用；2.0 × 0.612 = 1.224？
让我重新算：prompt 1000 * 0.001 = 1.0；completion 500 * 0.002 = 1.0；original = 2.0；final 0.612 -> total = 1.224。上面的 0.918 算错了，应改为 1.224。）

修正断言：
```java
assertThat(record.getTotalCost()).isEqualByComparingTo(new BigDecimal("1.224000"));
```

### Step 7.3: 运行测试

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=BillingServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS, tests PASS

### Step 7.4: 提交

```bash
git add src/test/java/org/unreal/modelrouter/billing/BillingServiceTest.java
git commit -m "test(billing): 验证账单折扣快照字段写入"
```

---

## Task 8: 全量编译与测试回归

**Files:** 无新增/修改

### Step 8.1: 全量编译

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS

### Step 8.2: 运行 billing 相关测试

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=DiscountCalculationServiceTest,BillingServiceTest,BalanceCheckServiceTest,BalanceDeductionServiceTest,FreeQuotaServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS, all tests PASS

### Step 8.3: 运行 adapter 相关测试

```bash
JAVA_HOME="/c/Program Files/Java/jdk-17.0.1" mvn test -Dtest=BaseAdapterExtendedTest,BaseAdapterFreeQuotaTest,BaseAdapterMetricsTest -Dcheckstyle.skip=true -Dspotbugs.skip=true
```
Expected: BUILD SUCCESS, all tests PASS

### Step 8.4: 提交

```bash
git commit --allow-empty -m "chore(billing): 折扣功能全量回归通过"
```

---

## Self-Review Checklist

### Spec Coverage

| Spec 需求 | 对应 Task |
|-----------|----------|
| `sldd_system_users.user_type` 冗余到账单 | Task 1, Task 5 |
| `ai_user_discount` 用户折扣实时查询 | Task 1, Task 4 |
| `ai_enterprise.subsidy_discount` 企业补贴折扣实时查询 | Task 1, Task 4 |
| 模型折扣保持不变 | Task 4 中 `resolveModelDiscountRate` 复用 `ModelPricingService` |
| 折上折（个人：模型×用户；企业：模型×用户×企业） | Task 4 `calculate()` |
| 实时读取不加缓存 | Task 4 直接查询 Repository |
| 各级折扣快照写入账单 | Task 1, Task 5 |
| 余额扣减基于折后金额 | Task 5 使用 `totalCost` |
| 请求前余额校验保持粗略 | 未改动 `BalanceCheckService` |

### Placeholder Scan

- 无 TBD/TODO
- 无 "add appropriate error handling" 等模糊描述
- 每个代码步骤包含完整代码
- 测试命令和预期输出明确

### Type Consistency

- `UserIdentity` 字段顺序与构造调用一致
- `BillingContext` getter/setter/builder 命名一致
- `DiscountCalculationService.calculate` 签名在 Task 4 和 Task 5 中一致
- `DiscountBreakdown` 字段名在实现和测试中一致

### 已知风险

- `BaseAdapter` 及其子类构造 `BillingContext` 的地方可能不止一处，需在 Task 6 中全局搜索 `BillingContext.create()` 并统一补充。
- `UserIdentity.SYSTEM` 常量需同步更新，否则编译失败。
- `PlatformDataSyncService` 中构造 `UserIdentity` 的地方可能有其他路径，需全局搜索 `new UserIdentity(` 确保全部更新。
- `BillingServiceTest` 若使用 Spring 上下文加载，需确保 `DiscountCalculationService` 已被 mock 或注入。

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-24-billing-discount-enterprise-personal-plan.md`.

Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
