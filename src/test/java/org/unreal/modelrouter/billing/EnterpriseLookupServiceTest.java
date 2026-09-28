package org.unreal.modelrouter.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformAccountBalanceEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformUserCompanyEntity;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformAccountBalanceRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformEnterpriseRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformUserCompanyRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 回归测试：企业用户低余额预警短信 companyName 参数缺失。
 * 根因：企业名称原先在 toInfo 中被 ai_enterprise（白名单表）查询结果"门控"，非白名单企业在 ai_enterprise 无记录，
 *       导致 enterpriseName/companyId 均为 null，短信模板报"companyName 缺失"。
 * 修复：企业名称直接以 sldd_system_users.company_id(=accountId) 查 sldd_system_user_company，不经过 ai_enterprise。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnterpriseLookupServiceTest {

    @Mock
    private PlatformSystemUserRepository systemUserRepository;
    @Mock
    private PlatformEnterpriseRepository enterpriseRepository;
    @Mock
    private PlatformUserCompanyRepository userCompanyRepository;
    @Mock
    private PlatformAccountBalanceRepository accountBalanceRepository;

    private EnterpriseLookupService service;

    private static final String COMPANY_ID = "2075410446389645312";

    @BeforeEach
    void setUp() {
        service = new EnterpriseLookupService(systemUserRepository, enterpriseRepository,
                userCompanyRepository, accountBalanceRepository);
    }

    @Test
    void enterpriseUserResolvesNameFromUserCompanyByCompanyId() {
        // Given: 余额账户存在；企业名称以 sldd_system_user_company 为准（与是否白名单 ai_enterprise 无关）
        when(accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(COMPANY_ID, 1))
                .thenReturn(Optional.of(balance(COMPANY_ID, 1)));
        when(userCompanyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company(COMPANY_ID, "Acme Ltd")));

        // When
        EnterpriseLookupService.EnterpriseInfo info = service.lookupByAccount(COMPANY_ID, 1).orElseThrow();

        // Then: 名称真实带上，短信 companyName 不再缺失
        assertThat(info.getAccountType()).isEqualTo(1);
        assertThat(info.getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(info.getEnterpriseName()).isEqualTo("Acme Ltd");
    }

    @Test
    void personalUserKeepsAccountIdAsName() {
        // Given: 个人用户（行为不变，不应受企业名称修复影响）
        String userId = "2075403526731763712";
        when(accountBalanceRepository.findByAccountIdAndAccountTypeAndDeletedFalse(userId, 2))
                .thenReturn(Optional.of(balance(userId, 2)));

        // When
        EnterpriseLookupService.EnterpriseInfo info = service.lookupByAccount(userId, 2).orElseThrow();

        // Then: 个人行为不变，enterpriseName = user_id，companyId 为空
        assertThat(info.getAccountType()).isEqualTo(2);
        assertThat(info.getEnterpriseName()).isEqualTo(userId);
        assertThat(info.getCompanyId()).isNull();
    }

    private PlatformAccountBalanceEntity balance(String accountId, Integer accountType) {
        PlatformAccountBalanceEntity entity = new PlatformAccountBalanceEntity();
        entity.setId(100L);
        entity.setAccountId(accountId);
        entity.setAccountType(accountType);
        entity.setBalance(new BigDecimal("9.85"));
        entity.setWarnEnabled(true);
        entity.setWarnThreshold(new BigDecimal("10.00"));
        return entity;
    }

    private PlatformUserCompanyEntity company(String id, String name) {
        PlatformUserCompanyEntity entity = new PlatformUserCompanyEntity();
        entity.setId(id);
        entity.setCompanyName(name);
        return entity;
    }
}
