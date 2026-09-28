# 免费 Tokens 额度实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 JAiRouter 中实现用户级免费 tokens 额度，优先扣减免费额度，超支时锁定并返回 402，命中免费额度的账单金额字段为 0 由后台映射为 “-”。

**Architecture:** 新增 `FreeQuotaService` 负责同步额度查询与原子扣减；`BalanceCheckService` 在请求前放行有额度的用户；`BaseAdapter` 与 `StreamingRequestProcessor` 在请求完成后同步调用额度扣减，超支时抛异常截断响应；`BillingService` 根据 `BillingContext` 中的免费额度结果异步记录账单并决定是否扣减余额。

**Tech Stack:** Spring Boot 3.5.5, WebFlux, JPA (Hibernate), PostgreSQL, Maven, JUnit 5, Mockito.

## Global Constraints

- JDK 17，Spring Boot 3.5.5 + WebFlux。
- 数据库为 PostgreSQL，`ai_billing_record` 由 JPA 自动建表；新增 `ai_user_free_quota` 同样由 JPA 实体驱动建表，DDL 仅作参考和手工执行备份。
- 免费额度仅对 `chat`、`embedding`、`rerank` 文本类服务生效。
- 按 `sldd_system_users.user_id` 维度统计（与 `UserIdentity.userId` 一致）。
- 免费额度不可透支；实际用量 > 剩余额度时原子设置 `trial_exhausted=true` 并返回 402。
- 所有数据库实体必须包含 `create_time`、`update_time`、`creator`、`updater`、`deleted` 字段。
- 所有代码修改需配套单元测试；计费链路修改需跑通现有 `BalanceCheckServiceTest` 与 `BalanceDeductionServiceTest`。

---

## 文件清单

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaEntity.java` | 创建 | JPA 实体：用户免费额度表 |
| `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaRepository.java` | 创建 | JPA 仓库：额度查询与更新 |
| `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaProperties.java` | 创建 | 免费额度配置属性 |
| `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaResult.java` | 创建 | 额度扣减结果对象 |
| `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaService.java` | 创建 | 额度查询、校验、原子扣减服务 |
| `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/BillingRecordEntity.java` | 修改 | 新增 `isFreeQuota`、`freeQuotaConsumed` |
| `src/main/java/org/unreal/modelrouter/billing/BillingService.java` | 修改 | 支持免费额度账单与余额扣减跳过 |
| `src/main/java/org/unreal/modelrouter/billing/BalanceCheckService.java` | 修改 | 请求前优先检查免费额度 |
| `src/main/java/org/unreal/modelrouter/router/adapter/BaseAdapter.java` | 修改 | 非流式请求完成后同步扣减额度 |
| `src/main/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessor.java` | 修改 | 流式请求完成后同步扣减额度 |
| `src/main/resources/config/billing/free-quota.yml` | 创建 | 默认配置 |
| `src/main/resources/db/scripts/V1__create_ai_user_free_quota.sql` | 创建 | PG DDL/DML 参考脚本 |
| `src/test/java/org/unreal/modelrouter/billing/freequota/FreeQuotaServiceTest.java` | 创建 | FreeQuotaService 单元测试 |
| `src/test/java/org/unreal/modelrouter/billing/BalanceCheckServiceTest.java` | 修改 | 免费额度场景测试 |
| `src/test/java/org/unreal/modelrouter/billing/BillingServiceTest.java` | 创建/修改 | 免费额度账单测试 |

---

### Task 1: 创建免费额度 JPA 实体与仓库

**Files:**
- Create: `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaEntity.java`
- Create: `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaRepository.java`

**Interfaces:**
- Produces: `FreeQuotaEntity`（含 `userId`、`totalQuota`、`usedQuota`、`remainingQuota`、`trialExhausted`、`exhaustedAt`、`version`、`createTime`、`updateTime`、`creator`、`updater`、`deleted`）
- Produces: `FreeQuotaRepository.findByUserIdAndDeletedFalse(String userId)`
- Produces: `FreeQuotaRepository.deductQuota(...)` / `markExhausted(...)` 更新方法

- [ ] **Step 1: 创建 FreeQuotaEntity**

```java
package org.unreal.modelrouter.billing.freequota;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Data
@Entity
@DynamicUpdate
@Table(name = "ai_user_free_quota", indexes = {
    @Index(name = "idx_ai_user_free_quota_user_id", columnList = "user_id")
})
public class FreeQuotaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 64)
    private String userId;

    @Column(name = "total_quota", nullable = false)
    private Long totalQuota = 1_000_000L;

    @Column(name = "used_quota", nullable = false)
    private Long usedQuota = 0L;

    @Column(name = "remaining_quota", nullable = false)
    private Long remainingQuota = 1_000_000L;

    @Column(name = "trial_exhausted", nullable = false)
    private Boolean trialExhausted = false;

    @Column(name = "exhausted_at")
    private LocalDateTime exhaustedAt;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @Column(name = "creator", length = 64)
    private String creator;

    @Column(name = "updater", length = 64)
    private String updater;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        if (updateTime == null) updateTime = now;
        if (deleted == null) deleted = false;
        if (totalQuota == null) totalQuota = 1_000_000L;
        if (usedQuota == null) usedQuota = 0L;
        if (remainingQuota == null) remainingQuota = totalQuota;
        if (trialExhausted == null) trialExhausted = false;
        if (version == null) version = 0L;
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: 创建 FreeQuotaRepository**

```java
package org.unreal.modelrouter.billing.freequota;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface FreeQuotaRepository extends JpaRepository<FreeQuotaEntity, Long> {

    Optional<FreeQuotaEntity> findByUserIdAndDeletedFalse(String userId);

    @Modifying
    @Query("UPDATE FreeQuotaEntity q SET q.usedQuota = q.usedQuota + :tokens, " +
           "q.remainingQuota = q.remainingQuota - :tokens, q.updateTime = CURRENT_TIMESTAMP, " +
           "q.updater = :updater, q.version = q.version + 1 " +
           "WHERE q.userId = :userId AND q.version = :version AND q.deleted = false")
    int deductQuota(@Param("userId") String userId,
                    @Param("tokens") long tokens,
                    @Param("updater") String updater,
                    @Param("version") long version);

    @Modifying
    @Query("UPDATE FreeQuotaEntity q SET q.trialExhausted = true, q.exhaustedAt = :exhaustedAt, " +
           "q.updateTime = CURRENT_TIMESTAMP, q.updater = :updater, q.version = q.version + 1 " +
           "WHERE q.userId = :userId AND q.version = :version AND q.deleted = false")
    int markExhausted(@Param("userId") String userId,
                      @Param("exhaustedAt") LocalDateTime exhaustedAt,
                      @Param("updater") String updater,
                      @Param("version") long version);
}
```

- [ ] **Step 3: 编译检查**

Run: `mvn compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/billing/freequota/
git commit -m "feat: 免费额度 JPA 实体与仓库"
```

---

### Task 2: 创建 FreeQuotaProperties 与 FreeQuotaResult

**Files:**
- Create: `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaProperties.java`
- Create: `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaResult.java`
- Create: `src/main/resources/config/billing/free-quota.yml`

**Interfaces:**
- Produces: `FreeQuotaProperties.enabled`、`defaultTotal`、`serviceTypes`
- Produces: `FreeQuotaResult.hitFreeQuota()`、`deductedTokens()`、`remainingBefore()`、`remainingAfter()`

- [ ] **Step 1: 创建 FreeQuotaProperties**

```java
package org.unreal.modelrouter.billing.freequota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "jairouter.billing.free-quota")
public class FreeQuotaProperties {

    private boolean enabled = true;
    private long defaultTotal = 1_000_000L;
    private Set<String> serviceTypes = new HashSet<>(Set.of("chat", "embedding", "rerank"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getDefaultTotal() { return defaultTotal; }
    public void setDefaultTotal(long defaultTotal) { this.defaultTotal = defaultTotal; }

    public Set<String> getServiceTypes() { return serviceTypes; }
    public void setServiceTypes(Set<String> serviceTypes) { this.serviceTypes = serviceTypes; }

    public boolean isFreeQuotaServiceType(String serviceType) {
        return serviceType != null && serviceTypes.contains(serviceType.toLowerCase());
    }
}
```

- [ ] **Step 2: 创建 FreeQuotaResult**

```java
package org.unreal.modelrouter.billing.freequota;

public record FreeQuotaResult(
    boolean hitFreeQuota,
    long deductedTokens,
    long remainingBefore,
    long remainingAfter
) {
    public static FreeQuotaResult miss(long remainingBefore) {
        return new FreeQuotaResult(false, 0L, remainingBefore, remainingBefore);
    }

    public static FreeQuotaResult hit(long deducted, long remainingBefore, long remainingAfter) {
        return new FreeQuotaResult(true, deducted, remainingBefore, remainingAfter);
    }
}
```

- [ ] **Step 3: 创建默认配置**

Create `src/main/resources/config/billing/free-quota.yml`:

```yaml
jairouter:
  billing:
    free-quota:
      enabled: true
      default-total: 1000000
      service-types:
        - chat
        - embedding
        - rerank
```

然后在 `src/main/resources/application.yml` 中 import：

```yaml
spring:
  config:
    import:
      - classpath:config/billing/free-quota.yml
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaProperties.java \
        src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaResult.java \
        src/main/resources/config/billing/free-quota.yml \
        src/main/resources/application.yml
git commit -m "feat: 免费额度配置属性与结果对象"
```

---

### Task 3: 创建 FreeQuotaService（核心扣减逻辑）

**Files:**
- Create: `src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaService.java`
- Test: `src/test/java/org/unreal/modelrouter/billing/freequota/FreeQuotaServiceTest.java`

**Interfaces:**
- Consumes: `FreeQuotaRepository`、`FreeQuotaProperties`
- Produces: `long getRemainingQuota(String userId, String serviceType)`
- Produces: `FreeQuotaResult deductQuota(String userId, String serviceType, long totalTokens)`
- Produces: `boolean isFreeQuotaEnabledFor(String userId, String serviceType)`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/org/unreal/modelrouter/billing/freequota/FreeQuotaServiceTest.java`:

```java
package org.unreal.modelrouter.billing.freequota;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FreeQuotaServiceTest {

    @Mock
    private FreeQuotaRepository repository;

    @Mock
    private FreeQuotaProperties properties;

    @InjectMocks
    private FreeQuotaService service;

    @Test
    void shouldHitFreeQuota_whenEnoughRemaining() {
        FreeQuotaEntity quota = quotaOf(1000L, 500L);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isFreeQuotaServiceType("chat")).thenReturn(true);
        when(repository.findByUserIdAndDeletedFalse("u1")).thenReturn(Optional.of(quota));
        when(repository.deductQuota("u1", 300L, "system", 0L)).thenReturn(1);

        FreeQuotaResult result = service.deductQuota("u1", "chat", 300L);

        assertThat(result.hitFreeQuota()).isTrue();
        assertThat(result.deductedTokens()).isEqualTo(300L);
    }

    @Test
    void shouldMissAndExhaust_whenTotalTokensExceedRemaining() {
        FreeQuotaEntity quota = quotaOf(1000L, 500L);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.isFreeQuotaServiceType("chat")).thenReturn(true);
        when(repository.findByUserIdAndDeletedFalse("u1")).thenReturn(Optional.of(quota));
        when(repository.markExhausted(eq("u1"), any(), eq("system"), eq(0L))).thenReturn(1);

        FreeQuotaResult result = service.deductQuota("u1", "chat", 600L);

        assertThat(result.hitFreeQuota()).isFalse();
        verify(repository).markExhausted(eq("u1"), any(), eq("system"), eq(0L));
    }

    private FreeQuotaEntity quotaOf(long total, long used) {
        FreeQuotaEntity q = new FreeQuotaEntity();
        q.setUserId("u1");
        q.setTotalQuota(total);
        q.setUsedQuota(used);
        q.setRemainingQuota(total - used);
        q.setTrialExhausted(false);
        q.setVersion(0L);
        q.setDeleted(false);
        return q;
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn test -Dtest=FreeQuotaServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: 编译失败（FreeQuotaService 不存在）

- [ ] **Step 3: 实现 FreeQuotaService**

```java
package org.unreal.modelrouter.billing.freequota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreeQuotaService {

    private static final String SYSTEM_UPDATER = "system";
    private static final int MAX_RETRIES = 3;

    private final FreeQuotaRepository repository;
    private final FreeQuotaProperties properties;

    public long getRemainingQuota(String userId, String serviceType) {
        if (!isEnabledFor(userId, serviceType)) {
            return 0L;
        }
        return repository.findByUserIdAndDeletedFalse(userId)
                .map(FreeQuotaEntity::getRemainingQuota)
                .orElse(0L);
    }

    public boolean isEnabledFor(String userId, String serviceType) {
        return properties.isEnabled()
                && userId != null
                && properties.isFreeQuotaServiceType(serviceType);
    }

    /**
     * 同步扣减免费额度。
     * 必须在请求处理主线程调用，以便超支时截断响应。
     */
    @Transactional
    public FreeQuotaResult deductQuota(String userId, String serviceType, long totalTokens) {
        if (!isEnabledFor(userId, serviceType) || totalTokens <= 0) {
            return FreeQuotaResult.miss(0);
        }

        int retries = 0;
        while (retries < MAX_RETRIES) {
            Optional<FreeQuotaEntity> optional = repository.findByUserIdAndDeletedFalse(userId);
            if (optional.isEmpty()) {
                return FreeQuotaResult.miss(0);
            }

            FreeQuotaEntity quota = optional.get();
            if (Boolean.TRUE.equals(quota.getTrialExhausted()) || quota.getRemainingQuota() <= 0) {
                return FreeQuotaResult.miss(quota.getRemainingQuota());
            }

            long remainingBefore = quota.getRemainingQuota();

            if (totalTokens <= remainingBefore) {
                int rows = repository.deductQuota(userId, totalTokens, SYSTEM_UPDATER, quota.getVersion());
                if (rows > 0) {
                    log.info("免费额度扣减成功, userId={}, tokens={}, remainingBefore={}, remainingAfter={}",
                            userId, totalTokens, remainingBefore, remainingBefore - totalTokens);
                    return FreeQuotaResult.hit(totalTokens, remainingBefore, remainingBefore - totalTokens);
                }
            } else {
                int rows = repository.markExhausted(userId, LocalDateTime.now(), SYSTEM_UPDATER, quota.getVersion());
                if (rows > 0) {
                    log.warn("免费额度超支锁定, userId={}, totalTokens={}, remaining={}",
                            userId, totalTokens, remainingBefore);
                    return FreeQuotaResult.miss(remainingBefore);
                }
            }

            retries++;
            log.debug("免费额度扣减乐观锁冲突, userId={}, retry={}", userId, retries);
        }

        log.error("免费额度扣减重试耗尽, userId={}, totalTokens={}", userId, totalTokens);
        return FreeQuotaResult.miss(0);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn test -Dtest=FreeQuotaServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: TESTS PASSED

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/billing/freequota/FreeQuotaService.java \
        src/test/java/org/unreal/modelrouter/billing/freequota/FreeQuotaServiceTest.java
git commit -m "feat: 免费额度扣减服务与单元测试"
```

---

### Task 4: 修改 BalanceCheckService

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/billing/BalanceCheckService.java`
- Test: `src/test/java/org/unreal/modelrouter/billing/BalanceCheckServiceTest.java`

**Interfaces:**
- Consumes: `FreeQuotaService`
- Produces: `BalanceCheckService` 增加 `freeQuotaService` 依赖

- [ ] **Step 1: 修改 BalanceCheckService**

```java
package org.unreal.modelrouter.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceCheckService {

    private final EnterpriseLookupService enterpriseLookupService;
    private final FreeQuotaService freeQuotaService;

    public Mono<Void> checkBalance(UserIdentity identity, String serviceType) {
        if (identity == null) {
            log.warn("余额校验跳过: identity 为 null");
            return Mono.empty();
        }

        if (identity.platformUser() && identity.enterpriseId() == null) {
            log.warn("企业白名单校验失败: platformUser=true 但 enterpriseId 为 null, user={}", identity.userAccount());
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "企业不在白名单中或已被移出白名单，请联系管理员。"
            ));
        }

        // 平台用户且属于文本类服务：优先检查免费额度
        if (identity.platformUser()
                && freeQuotaService.isEnabledFor(identity.userId(), serviceType)
                && freeQuotaService.getRemainingQuota(identity.userId(), serviceType) > 0) {
            log.info("免费额度校验通过, user={}, serviceType={}", identity.userId(), serviceType);
            return Mono.empty();
        }

        if (identity.enterpriseId() == null) {
            log.info("余额校验跳过: enterpriseId 为 null, user={}", identity.userAccount());
            return Mono.empty();
        }

        return Mono.fromCallable(() -> enterpriseLookupService.lookupByEnterpriseId(identity.enterpriseId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optInfo -> {
                    if (optInfo.isEmpty()) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "企业不在白名单中或已被移出白名单，请联系管理员。"
                        ));
                    }
                    EnterpriseLookupService.EnterpriseInfo info = optInfo.get();
                    if (info.getBalance().compareTo(BigDecimal.ZERO) < 0) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.PAYMENT_REQUIRED,
                                "企业余额不足，请充值。"
                        ));
                    }
                    return Mono.empty();
                });
    }
}
```

**注意**：`BaseAdapter` 中调用 `checkBalance(identity)` 的地方需要改为 `checkBalance(identity, serviceType.name())`。

- [ ] **Step 2: 更新 BalanceCheckServiceTest**

新增测试用例（使用 Mockito）：

```java
@Test
void shouldPass_whenFreeQuotaAvailable() {
    UserIdentity identity = new UserIdentity("u1", "u1@example.com", "1", "key", 1L, "ent", "c1", true);
    when(freeQuotaService.isEnabledFor("u1", "chat")).thenReturn(true);
    when(freeQuotaService.getRemainingQuota("u1", "chat")).thenReturn(100L);

    StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
            .verifyComplete();
}

@Test
void shouldCheckBalance_whenFreeQuotaExhausted() {
    UserIdentity identity = new UserIdentity("u1", "u1@example.com", "1", "key", 1L, "ent", "c1", true);
    when(freeQuotaService.isEnabledFor("u1", "chat")).thenReturn(true);
    when(freeQuotaService.getRemainingQuota("u1", "chat")).thenReturn(0L);
    when(enterpriseLookupService.lookupByEnterpriseId(1L))
            .thenReturn(Optional.of(infoWithBalance(BigDecimal.TEN)));

    StepVerifier.create(balanceCheckService.checkBalance(identity, "chat"))
            .verifyComplete();
}
```

- [ ] **Step 3: 运行 BalanceCheckServiceTest**

Run: `mvn test -Dtest=BalanceCheckServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: TESTS PASSED

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/billing/BalanceCheckService.java \
        src/test/java/org/unreal/modelrouter/billing/BalanceCheckServiceTest.java
git commit -m "feat: BalanceCheckService 支持免费额度优先校验"
```

---

### Task 5: 修改 BillingRecordEntity 与 BillingContext

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/persistence/jpa/entity/BillingRecordEntity.java`
- Modify: `src/main/java/org/unreal/modelrouter/billing/BillingService.java`（内部类 BillingContext）

**Interfaces:**
- Produces: `BillingRecordEntity.isFreeQuota`、`freeQuotaConsumed`
- Produces: `BillingService.BillingContext.isFreeQuota(Long)`、`freeQuotaConsumed(Long)`

- [ ] **Step 1: 修改 BillingRecordEntity**

在 `totalCost` 字段后新增：

```java
@Column(name = "is_free_quota", nullable = false)
private Boolean isFreeQuota = false;

@Column(name = "free_quota_consumed", nullable = false)
private Long freeQuotaConsumed = 0L;
```

- [ ] **Step 2: 修改 BillingService.BillingContext**

新增字段与 builder 方法：

```java
private Boolean isFreeQuota = false;
private Long freeQuotaConsumed = 0L;

public BillingContext isFreeQuota(Boolean v) { this.isFreeQuota = v; return this; }
public BillingContext freeQuotaConsumed(Long v) { this.freeQuotaConsumed = v; return this; }

public Boolean getIsFreeQuota() { return isFreeQuota; }
public Long getFreeQuotaConsumed() { return freeQuotaConsumed; }
```

- [ ] **Step 3: 修改 BillingService.buildRecord()**

在 `buildRecord` 方法中，设置 `isFreeQuota` 与 `freeQuotaConsumed`：

```java
return BillingRecordEntity.builder()
        // ... 原有字段 ...
        .isFreeQuota(ctx.getIsFreeQuota())
        .freeQuotaConsumed(ctx.getFreeQuotaConsumed())
        .build();
```

若 `ctx.getIsFreeQuota()` 为 true，则覆盖金额字段为 0：

```java
if (Boolean.TRUE.equals(ctx.getIsFreeQuota())) {
    originalCost = BigDecimal.ZERO;
    discountAmount = BigDecimal.ZERO;
    totalCost = BigDecimal.ZERO;
}
```

- [ ] **Step 4: 修改 BillingService.recordBilling() 余额扣减判断**

```java
if (Boolean.TRUE.equals(ctx.getIsFreeQuota()) ||
    record.getTotalCost() == null ||
    record.getTotalCost().compareTo(BigDecimal.ZERO) <= 0) {
    log.info("跳过余额扣减: isFreeQuota={}, cost={}", ctx.getIsFreeQuota(), record.getTotalCost());
    return;
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/persistence/jpa/entity/BillingRecordEntity.java \
        src/main/java/org/unreal/modelrouter/billing/BillingService.java
git commit -m "feat: 账单实体与上下文支持免费额度标记"
```

---

### Task 6: 修改 BaseAdapter（非流式请求同步扣减）

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/router/adapter/BaseAdapter.java`

**Interfaces:**
- Consumes: `FreeQuotaService`、`FreeQuotaResult`
- Produces: `BaseAdapter` 中构建的 `BillingContext` 携带免费额度结果

- [ ] **Step 1: 修改 BaseAdapter.recordBilling()**

在 `recordBilling` 方法中，先同步调用 `FreeQuotaService.deductQuota()`，再把结果放入 `BillingContext`：

```java
private void recordBilling(...) {
    try {
        FreeQuotaService freeQuotaService = ApplicationContextProvider.getBean(FreeQuotaService.class);
        FreeQuotaResult freeQuotaResult = null;
        if (identity != null && identity.platformUser()
                && freeQuotaService.isEnabledFor(identity.userId(), serviceType.name())) {
            freeQuotaResult = freeQuotaService.deductQuota(
                    identity.userId(), serviceType.name(), totalTokens);

            if (!freeQuotaResult.hitFreeQuota()) {
                // 实际用量 > 剩余额度：截断响应
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                        "体验额度耗尽，生成失败");
            }
        }

        var billingService = ApplicationContextProvider.getBean(BillingService.class);
        var ctx = BillingService.BillingContext.create()
                // ... 原有字段 ...
                .isFreeQuota(freeQuotaResult != null && freeQuotaResult.hitFreeQuota())
                .freeQuotaConsumed(freeQuotaResult != null ? freeQuotaResult.deductedTokens() : 0L)
                // ...
                ;
        billingService.recordBilling(ctx);
    } catch (Exception e) {
        // 若是额度耗尽的业务异常，继续向上抛，截断响应
        if (e instanceof org.springframework.web.server.ResponseStatusException rse
                && rse.getStatusCode() == org.springframework.http.HttpStatus.PAYMENT_REQUIRED) {
            throw e;
        }
        logger.error("计费记录异常...", e);
    }
}
```

**注意**：`BaseAdapter` 已通过 `ApplicationContextProvider.getBean(...)` 获取 `BillingService` 等依赖。为避免修改全部 6 个子类构造方法，`FreeQuotaService` 同样按需从上下文获取：

```java
FreeQuotaService freeQuotaService = ApplicationContextProvider.getBean(FreeQuotaService.class);
```

- [ ] **Step 2: 更新 BaseAdapter 调用 checkBalance 签名**

将 `balanceCheckService.checkBalance(identity)` 改为 `checkBalance(identity, serviceType.name())`。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/router/adapter/BaseAdapter.java
git commit -m "feat: 非流式请求同步扣减免费额度并截断超支响应"
```

---

### Task 7: 修改 StreamingRequestProcessor（流式请求同步扣减）

**Files:**
- Modify: `src/main/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessor.java`

**Interfaces:**
- Consumes: `FreeQuotaService`、`FreeQuotaResult`
- Produces: `recordStreamingTokenUsage` 携带免费额度结果到 `BillingContext`

- [ ] **Step 1: 注入 FreeQuotaService**

```java
private final FreeQuotaService freeQuotaService;

public StreamingRequestProcessor(..., FreeQuotaService freeQuotaService) {
    // ...
    this.freeQuotaService = freeQuotaService;
}
```

- [ ] **Step 2: 在 recordStreamingTokenUsage 中同步扣减**

```java
private void recordStreamingTokenUsage(...) {
    // ... 原有代码 ...

    FreeQuotaResult freeQuotaResult = null;
    if (identity.platformUser()
            && freeQuotaService.isEnabledFor(identity.userId(), serviceType.name())) {
        freeQuotaResult = freeQuotaService.deductQuota(
                identity.userId(), serviceType.name(), totalTokens);

        if (!freeQuotaResult.hitFreeQuota()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                    "体验额度耗尽，生成失败");
        }
    }

    // 流式计费记录
    var ctx = BillingService.BillingContext.create()
            // ... 原有字段 ...
            .isFreeQuota(freeQuotaResult != null && freeQuotaResult.hitFreeQuota())
            .freeQuotaConsumed(freeQuotaResult != null ? freeQuotaResult.deductedTokens() : 0L)
            // ...
            ;
    billingService.recordBilling(ctx);
}
```

**注意**：抛出的 `ResponseStatusException` 会在流式结束时让 SSE 流以错误终止；已发送的 chunk 无法撤回。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/unreal/modelrouter/router/adapter/processor/StreamingRequestProcessor.java
git commit -m "feat: 流式请求同步扣减免费额度并截断超支响应"
```

---

### Task 8: 创建 DDL/DML 参考脚本

**Files:**
- Create: `src/main/resources/db/scripts/V1__create_ai_user_free_quota.sql`

- [ ] **Step 1: 创建 SQL 脚本**

```sql
-- ============================================================
-- 免费额度表 ai_user_free_quota
-- 由 JPA 自动建表；此脚本作为手工执行/运维参考
-- ============================================================

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

CREATE INDEX IF NOT EXISTS idx_ai_user_free_quota_user_id ON ai_user_free_quota (user_id);

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

-- ai_billing_record 新增免费额度字段
ALTER TABLE ai_billing_record
    ADD COLUMN IF NOT EXISTS is_free_quota BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ai_billing_record
    ADD COLUMN IF NOT EXISTS free_quota_consumed BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN ai_billing_record.is_free_quota IS '是否命中免费额度：true=本次请求走免费额度，金额字段为0';
COMMENT ON COLUMN ai_billing_record.free_quota_consumed IS '本次实际消耗的免费 token 数';

-- ============================================================
-- 常用 DML 示例
-- ============================================================

-- 1. 初始化用户免费额度（算力平台执行）
INSERT INTO ai_user_free_quota (
    user_id, total_quota, used_quota, remaining_quota,
    trial_exhausted, create_time, update_time, creator, updater, deleted, version
) VALUES (
    'u123456', 1000000, 0, 1000000,
    false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system', false, 0
);

-- 2. 查询用户剩余额度
SELECT id, user_id, total_quota, used_quota, remaining_quota,
       trial_exhausted, version
FROM ai_user_free_quota
WHERE user_id = 'u123456' AND deleted = false;

-- 3. 查询试用账单（后台映射金额字段为 '-'）
SELECT id, user_id, api_key_id, api_key_name, model_name, service_type,
       total_tokens, is_free_quota, free_quota_consumed,
       original_cost, discount_amount, total_cost
FROM ai_billing_record
WHERE is_free_quota = true AND is_deleted = false
ORDER BY started_at DESC;

-- 4. 查询额度使用统计
SELECT user_id, total_quota, used_quota, remaining_quota,
       ROUND(used_quota * 100.0 / total_quota, 2) AS usage_rate,
       trial_exhausted, exhausted_at
FROM ai_user_free_quota
WHERE user_id = 'u123456' AND deleted = false;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/scripts/V1__create_ai_user_free_quota.sql
git commit -m "docs: 免费额度 PG DDL/DML 参考脚本"
```

---

### Task 9: 计费服务集成测试

**Files:**
- Test: `src/test/java/org/unreal/modelrouter/billing/BillingServiceTest.java`

**Interfaces:**
- Consumes: `BillingService`、`FreeQuotaService`、`BalanceDeductionService`、`BillingRecordRepository`

- [ ] **Step 1: 创建 BillingServiceTest**

```java
package org.unreal.modelrouter.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.billing.freequota.FreeQuotaResult;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock private BillingRecordRepository billingRepository;
    @Mock private ModelPricingService pricingService;
    @Mock private BalanceDeductionService balanceDeductionService;
    @Mock private EnterpriseLookupService enterpriseLookupService;
    @Mock private FreeQuotaService freeQuotaService;

    @InjectMocks
    private BillingService billingService;

    @Test
    void shouldRecordFreeQuotaBill_whenHitFreeQuota() {
        BillingService.BillingContext ctx = BillingService.BillingContext.create()
                .userId("u1")
                .modelName("gpt-4")
                .serviceType("chat")
                .totalTokens(100L)
                .enterpriseId(1L)
                .isFreeQuota(true)
                .freeQuotaConsumed(100L);

        billingService.recordBilling(ctx);

        ArgumentCaptor<BillingRecordEntity> captor = ArgumentCaptor.forClass(BillingRecordEntity.class);
        verify(billingRepository).save(captor.capture());
        BillingRecordEntity record = captor.getValue();
        assertThat(record.getIsFreeQuota()).isTrue();
        assertThat(record.getFreeQuotaConsumed()).isEqualTo(100L);
        assertThat(record.getTotalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(balanceDeductionService, never()).deductAndAlert(any(), any(), any());
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -Dtest=BillingServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: TESTS PASSED

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/unreal/modelrouter/billing/BillingServiceTest.java
git commit -m "test: 免费额度账单测试"
```

---

### Task 10: 全量编译与测试回归

- [ ] **Step 1: 编译**

Run: `mvn clean compile -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行 billing 包测试**

Run: `mvn test -Dtest=FreeQuotaServiceTest,BalanceCheckServiceTest,BillingServiceTest,BalanceDeductionServiceTest -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: TESTS PASSED

- [ ] **Step 3: 全量测试**

Run: `mvn test -Dcheckstyle.skip=true -Dspotbugs.skip=true`
Expected: BUILD SUCCESS（允许已有失败，需单独评估）

- [ ] **Step 4: Commit / Finalize**

```bash
git status
git log --oneline -10
```

---

## Self-Review Checklist

- [ ] **Spec coverage**: 每个设计文档章节都有对应任务。
- [ ] **Placeholder scan**: 无 TBD/TODO/实现占位符。
- [ ] **Type consistency**: `FreeQuotaResult`、`BillingContext`、`BillingRecordEntity` 字段名一致。
- [ ] **线程安全**: 免费额度扣减在主线程同步执行；账单记录仍异步。
- [ ] **响应截断**: 非流式/流式均在扣减失败时抛 `ResponseStatusException`。
- [ ] **审计字段**: `FreeQuotaEntity` 包含 `create_time`/`update_time`/`creator`/`updater`/`deleted`。
- [ ] **DDL/DML**: 已提供带注释的 PG 脚本。
