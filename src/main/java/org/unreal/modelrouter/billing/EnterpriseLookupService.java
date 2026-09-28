package org.unreal.modelrouter.billing;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformAccountBalanceEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformEnterpriseEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformUserCompanyEntity;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformAccountBalanceRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformEnterpriseRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformUserCompanyRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 企业/账户查询服务。
 * - 企业基础信息、补贴折扣从 ai_enterprise 读取。
 * - 余额、预警配置从 ai_account_balance 读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnterpriseLookupService {

    private final PlatformSystemUserRepository systemUserRepository;
    private final PlatformEnterpriseRepository enterpriseRepository;
    private final PlatformUserCompanyRepository userCompanyRepository;
    private final PlatformAccountBalanceRepository accountBalanceRepository;

    /**
     * 通过平台用户 ID 查找账户信息。
     * 链路：ai_api_key.user_id → sldd_system_users.id(PK) → 按 userType 确定 accountId/accountType → ai_account_balance
     *
     * @param platformUserId ai_api_key.user_id（对应 sldd_system_users.id 主键）
     */
    public Optional<EnterpriseInfo> lookupByUserId(Long platformUserId) {
        if (platformUserId == null) {
            return Optional.empty();
        }
        try {
            return systemUserRepository.findById(platformUserId)
                    .flatMap(user -> {
                        String accountId;
                        Integer accountType = user.getUserType();
                        if (Integer.valueOf(1).equals(accountType)) {
                            accountId = user.getCompanyId();
                        } else if (Integer.valueOf(2).equals(accountType)) {
                            accountId = user.getUserId();
                        } else {
                            log.warn("未知用户类型, platformUserId={}, userType={}", platformUserId, accountType);
                            return Optional.empty();
                        }
                        if (accountId == null || accountId.isBlank()) {
                            log.warn("账户ID为空, platformUserId={}, userType={}", platformUserId, accountType);
                            return Optional.empty();
                        }
                        return lookupByAccount(accountId, accountType);
                    });
        } catch (Exception e) {
            log.error("账户查询失败, platformUserId={}: {}", platformUserId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 直接通过企业 ID 查询，带余额校验。
     * 链路：ai_enterprise.id → company_id → ai_account_balance(accountType=1)
     */
    public Optional<EnterpriseInfo> lookupByEnterpriseId(Long enterpriseId) {
        if (enterpriseId == null) {
            return Optional.empty();
        }
        try {
            return enterpriseRepository.findById(enterpriseId)
                    .filter(e -> e.getDeleted() == null || !e.getDeleted())
                    .flatMap(e -> {
                        String companyId = e.getCompanyId();
                        if (companyId == null || companyId.isBlank()) {
                            log.warn("企业 company_id 为空, enterpriseId={}", enterpriseId);
                            return Optional.empty();
                        }
                        return lookupByAccount(companyId, 1);
                    });
        } catch (Exception e) {
            log.error("企业查询失败, enterpriseId={}: {}", enterpriseId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 通过 accountId + accountType 直接查询账户信息。
     */
    public Optional<EnterpriseInfo> lookupByAccount(String accountId, Integer accountType) {
        if (accountId == null || accountId.isBlank() || accountType == null) {
            return Optional.empty();
        }
        try {
            Optional<PlatformAccountBalanceEntity> balanceOpt =
                    accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(accountId, accountType);
            return Optional.of(toInfo(accountId, accountType, balanceOpt.orElse(null)));
        } catch (Exception e) {
            log.error("账户查询失败, accountId={}, accountType={}: {}", accountId, accountType, e.getMessage());
            return Optional.empty();
        }
    }

    private EnterpriseInfo toInfo(String accountId, Integer accountType,
                                  PlatformAccountBalanceEntity balance) {
        EnterpriseInfo info = new EnterpriseInfo();
        info.setAccountType(accountType);

        // 账户名称
        if (accountType == 1) {
            // accountId = sldd_system_users.company_id = sldd_system_user_company.id，
            // 直接查 sldd_system_user_company 取企业真实名称。
            // 不经过 ai_enterprise：该表是白名单，非白名单企业无记录，会导致名称查不到、短信 companyName 缺失。
            info.setCompanyId(accountId);
            String companyName = userCompanyRepository.findById(accountId)
                    .map(PlatformUserCompanyEntity::getCompanyName)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(null);
            info.setEnterpriseName(companyName);
        } else if (accountType == 2) {
            info.setEnterpriseName(accountId);
        }

        // 余额/预警信息
        if (balance != null) {
            info.setBalance(balance.getBalance() != null ? balance.getBalance() : BigDecimal.ZERO);
            info.setWarnEnabled(balance.getWarnEnabled());
            info.setWarnThreshold(balance.getWarnThreshold());
            info.setLastWarnTime(balance.getLastWarnTime());
            info.setLastWarnThreshold(balance.getLastWarnThreshold());
        } else {
            info.setBalance(BigDecimal.ZERO);
        }
        return info;
    }

    @Data
    public static class EnterpriseInfo {
        private Long enterpriseId;
        private String enterpriseName;
        private String companyId;
        /** 账户类型（1=企业, 2=个人），用于短信模板等场景区分用户类型 */
        private Integer accountType;
        private BigDecimal balance;
        private Boolean warnEnabled;
        private BigDecimal warnThreshold;
        private LocalDateTime lastWarnTime;
        /** 上次预警阈值，NULL 表示当前在阈值上方 */
        private BigDecimal lastWarnThreshold;
        /** 请求用户 ID（由 BillingService 从 BillingContext 设置，用于短信通知） */
        private String userId;
        /** 用户手机号（由 BillingService 从 sldd_system_users 查询设置） */
        private String mobile;
    }
}
