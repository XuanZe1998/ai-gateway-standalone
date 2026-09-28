# JAiRouter 系统表精简方案

## 一、现状分析

### 1.1 数据库环境
- JAiRouter 与算力平台共用 PostgreSQL 实例
- ddl-auto: none，Hibernate 不自动建表
- schema.sql 使用 MySQL/H2 语法（AUTO_INCREMENT 等），不兼容 PostgreSQL
- 启动日志大量报错：relation "config_data" / "service_instance" / "security_blacklist" does not exist

### 1.2 现有表分类

#### 算力平台表（本项目只读映射，已存在）
| 表名 | 实体类 | 说明 |
|------|--------|------|
| ai_model | PlatformModelEntity | 模型配置 |
| ai_channel | PlatformChannelEntity | 渠道配置 |
| ai_api_key | PlatformApiKeyEntity | API Key |
| ai_enterprise | PlatformEnterpriseEntity | 企业信息 |

#### 本项目系统表（JPA Entity 定义）
| 表名 | 实体类 | schema.sql 定义 | 当前状态 |
|------|--------|-----------------|----------|
| config_data | ConfigEntity | 有 | 不存在 |
| service_config | ServiceConfigEntity | 有 | 不存在 |
| service_instance | ServiceInstanceEntity | 有 | 不存在 |
| config_version_history | ConfigVersionHistoryEntity | 无 | 不存在 |
| config_audit_log | ConfigAuditLogEntity | 无 | 不存在 |
| config_change_audit_log | ConfigChangeAuditEntity | 无 | 不存在 |
| instance_circuit_breaker | InstanceCircuitBreakerEntity | 无 | 不存在 |
| instance_rate_limit | InstanceRateLimitEntity | 无 | 不存在 |
| security_blacklist | SecurityBlacklistEntity | 注释说明由JPA管理 | 不存在 |
| security_audit | SecurityAuditEventEntity | 有 | 不存在 |
| api_keys | (无对应Entity) | 有 | 不存在 |
| jwt_accounts | JwtAccountEntity | 有 | 不存在 |
| jwt_blacklist | (无对应Entity) | 有 | 不存在 |
| exception_events | ExceptionEventEntity | 有 | 不存在 |
| exception_stats_hourly | ExceptionStatsHourlyEntity | 有 | 不存在 |
| token_usage | TokenUsageEntity | 有 | 不存在 |
| billing_record | BillingRecordEntity | 无 | **已存在** |

---

## 二、精简方案

### 2.1 保留表（必须创建）

| 表名 | 理由 | 优先级 |
|------|------|--------|
| **billing_record** | 已有数据，计费核心，必须保留 | P0 |
| **security_blacklist** | 安全功能独立，无替代方案 | P1 |
| **jwt_accounts** | 本地JWT认证需要（如启用） | P1 |
| **token_usage** | Token统计功能，可选但建议保留 | P2 |
| **exception_events** | 异常监控，可选但建议保留 | P2 |
| **security_audit** | 安全审计日志，可选但建议保留 | P2 |

### 2.2 废弃表（不再创建）

| 表名 | 废弃理由 | 替代方案 |
|------|----------|----------|
| **config_data** | 配置已从YAML/内存加载，算力平台有ai_model/ai_channel | YAML文件 + 算力平台表 |
| **service_config** | 同上，运行时从YAML配置 | YAML文件 |
| **service_instance** | 同上，实例信息在YAML中 | YAML文件 |
| **config_version_history** | 依赖config_data的版本管理 | YAML文件版本控制 |
| **config_audit_log** | 依赖config_data的审计 | 日志文件 |
| **config_change_audit_log** | 同上 | 日志文件 |
| **instance_circuit_breaker** | 熔断器状态走Redis/File持久化 | Redis + FileStoreManager |
| **instance_rate_limit** | 限流配置在YAML中 | YAML配置 |
| **api_keys** | schema.sql中有定义但无Entity类，实际未使用；算力平台有ai_api_key | 算力平台ai_api_key表 |
| **jwt_blacklist** | 无对应Entity，实际使用StoreManager存储 | StoreManager(config_data替代) |
| **exception_stats_hourly** | 可由exception_events聚合得到 | 实时聚合计算 |

### 2.3 废弃功能

| 功能 | 废弃理由 |
|------|----------|
| 数据库配置版本管理 | 配置已走YAML，无需DB版本控制 |
| 数据库存储的服务实例健康状态 | 从YAML运行时获取，无需持久化 |
| 数据库配置审计 | 配置变更频率低，日志足够 |
| 数据库JWT黑名单 | 使用StoreManager(文件/内存)存储 |
| 数据库API Key管理 | 使用算力平台ai_api_key |

---

## 三、PostgreSQL 兼容 DDL 脚本

见下方完整DDL。

---

## 四、代码修改计划

### 4.1 废弃实体类（标记为 @Deprecated，后续删除）

| 文件路径 | 操作 |
|----------|------|
| persistence/jpa/entity/ConfigEntity.java | 标记废弃 |
| persistence/jpa/entity/ServiceConfigEntity.java | 标记废弃 |
| persistence/jpa/entity/ServiceInstanceEntity.java | 标记废弃 |
| persistence/jpa/entity/ConfigVersionHistoryEntity.java | 标记废弃 |
| persistence/jpa/entity/ConfigAuditLogEntity.java | 标记废弃 |
| persistence/jpa/entity/InstanceCircuitBreakerEntity.java | 标记废弃 |
| persistence/jpa/entity/InstanceRateLimitEntity.java | 标记废弃 |
| auth/audit/ConfigChangeAuditEntity.java | 标记废弃 |

### 4.2 废弃 Repository 接口（标记为 @Deprecated）

| 文件路径 | 操作 |
|----------|------|
| persistence/jpa/repository/ConfigRepository.java | 标记废弃 |
| persistence/jpa/repository/ServiceConfigRepository.java | 标记废弃 |
| persistence/jpa/repository/ServiceInstanceRepository.java | 标记废弃 |
| persistence/jpa/repository/ConfigVersionHistoryRepository.java | 标记废弃 |
| persistence/jpa/repository/ConfigAuditLogRepository.java | 标记废弃 |
| persistence/jpa/repository/InstanceCircuitBreakerRepository.java | 标记废弃 |
| persistence/jpa/repository/InstanceRateLimitRepository.java | 标记废弃 |
| auth/audit/ConfigChangeAuditRepository.java | 标记废弃 |

### 4.3 修改 JpaDatabaseInitializer

文件: persistence/jpa/JpaDatabaseInitializer.java

修改内容:
1. 移除 ConfigRepository、ServiceConfigRepository、ServiceInstanceRepository 依赖
2. 移除 initializeModelConfig() 和 initializeFromYaml() 方法
3. 保留 initializeApiKeys() 和 initializeJwtAccounts()
4. 整体用 try-catch 包裹，表不存在时不影响启动

### 4.4 修改 StoreManagerConfiguration

文件: persistence/store/StoreManagerConfiguration.java

修改内容: 将默认存储从 JPA 切换为 File，添加 JPA 不可用时的回退逻辑。

### 4.5 修改 JpaStoreManager

文件: persistence/jpa/JpaStoreManager.java

修改内容: 添加 @ConditionalOnProperty 使其可禁用。

### 4.6 删除/禁用 schema.sql

文件: resources/schema.sql

操作: 重命名为 schema.sql.deprecated 或删除，确保 spring.sql.init.schema-locations 不指向此文件。

### 4.7 修改 DatabaseMigrationService

文件: persistence/jpa/DatabaseMigrationService.java

修改内容: 移除或修改 migrateSecurityBlacklistExpiresAt() 迁移逻辑。

---

## 五、配置变更

### 5.1 postgresql.yml 新增配置

```yaml
spring:
  sql:
    init:
      mode: never      # 禁用 schema.sql/data.sql 自动执行

jairouter:
  persistence:
    jpa:
      store:
        enabled: false  # 禁用 JPA StoreManager，使用 FileStoreManager
    file:
      path: ./data/config  # 文件存储路径
```

---

## 六、影响评估

### 6.1 对核心路由功能的影响：无
- 核心路由（请求转发、负载均衡、熔断器）完全不依赖数据库表
- 配置从 YAML 文件加载，运行时从内存读取
- 所有组件都有优雅降级（@Autowired(required=false) + try-catch）

### 6.2 对配置管理功能的影响：轻微
- 配置版本管理从数据库切换到文件系统
- 版本历史存储在 ./data/config/ 目录下
- 配置变更审计转为日志记录

### 6.3 对监控功能的影响：无
- Token 用量统计保留（token_usage 表）
- 异常追踪保留（exception_events 表）
- 安全审计保留（security_audit 表）

### 6.4 对认证功能的影响：轻微
- JWT 账户保留（jwt_accounts 表）
- API Key 认证使用算力平台 ai_api_key 表
- JWT 黑名单使用 StoreManager（文件/内存）

### 6.5 对计费功能的影响：无
- billing_record 表保留，已有数据不受影响

---

## 七、迁移步骤

### Step 1: 准备阶段（零停机）
1. 备份数据: pg_dump 备份 schema 和 billing_record 数据
2. 创建新 DDL 脚本 schema-pg.sql

### Step 2: 执行 DDL（零停机）
1. 在 PostgreSQL 中执行保留表的创建
2. 验证表创建成功

### Step 3: 代码修改（需要重启）
1. 按第4节修改相关 Java 文件
2. 修改配置 YAML 文件
3. 重命名/删除 schema.sql
4. 编译验证: mvn clean compile
5. 部署重启

### Step 4: 验证阶段
1. 检查启动日志，确认无报错
2. 验证核心路由功能
3. 验证计费记录写入
4. 验证 Token 统计

### Step 5: 清理阶段（一周后）
1. 删除废弃表（如果之前存在）
2. 删除废弃代码（标记为 @Deprecated 的类）

---

## 八、回滚方案

如果出现问题，可快速回滚：
1. 回滚代码: 使用 Git 回滚到上一个版本
2. 恢复 schema.sql: 恢复原始的 schema.sql 文件
3. 重新创建废弃表: 从备份恢复表结构
4. 切换 StoreManager: 将 FileStoreManager 切换回 JpaStoreManager

---

## 九、关键文件清单

### 需要修改的文件

| 优先级 | 文件路径 | 修改内容 |
|--------|----------|----------|
| P0 | src/main/resources/schema.sql | 替换为 PostgreSQL 兼容版本或删除 |
| P0 | persistence/jpa/JpaDatabaseInitializer.java | 移除废弃表初始化逻辑 |
| P0 | persistence/store/StoreManagerConfiguration.java | 添加 FileStoreManager 回退 |
| P0 | config/persistence/postgresql.yml | 添加功能开关配置 |
| P1 | persistence/jpa/JpaStoreManager.java | 添加 @ConditionalOnProperty |
| P1 | persistence/jpa/DatabaseMigrationService.java | 移除或修改迁移逻辑 |
| P2 | 多个 Entity 文件 | 标记 @Deprecated |
| P2 | 多个 Repository 文件 | 标记 @Deprecated |

### 需要创建的 DDL 文件

| 文件路径 | 说明 |
|----------|------|
| src/main/resources/schema-pg.sql | PostgreSQL 兼容的精简 DDL |

---

## 十、总结

### 精简前后对比

| 指标 | 精简前 | 精简后 |
|------|--------|--------|
| 系统表数量 | 18+ | 6 |
| schema.sql 兼容性 | MySQL/H2，不兼容 PG | PostgreSQL 原生 |
| 启动报错 | 大量 relation does not exist | 无 |
| 核心功能依赖 | 部分依赖数据库配置 | 完全从 YAML 加载 |
| 配置持久化 | JPA (数据库) | FileStoreManager (文件) |

### 核心收益

1. 消除启动报错: 废弃不兼容的表，消除启动噪音
2. 简化架构: 从 18+ 表精简到 6 表，降低维护成本
3. PostgreSQL 原生兼容: DDL 使用 PostgreSQL 语法
4. 功能无损: 核心路由、计费、监控功能完全保留
5. 优雅降级: 所有组件都有降级策略，单点故障不影响核心功能

---

## 附录：完整 PostgreSQL DDL 脚本

见 schema-pg.sql 文件（需单独创建）。
