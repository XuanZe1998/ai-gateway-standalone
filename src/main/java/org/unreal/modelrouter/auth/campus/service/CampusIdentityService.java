package org.unreal.modelrouter.auth.campus.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.campus.model.CasIdentity;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.freequota.FreeQuotaService;
import org.unreal.modelrouter.persistence.jpa.entity.CampusIdentityBindingEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformSystemUserEntity;
import org.unreal.modelrouter.persistence.jpa.repository.CampusIdentityBindingRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformSystemUserRepository;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 将 CAS 身份映射为独立平台用户和基础应用权限。 */
@Service
@RequiredArgsConstructor
public class CampusIdentityService {
    private final CampusAuthProperties properties;
    private final PlatformSystemUserRepository systemUserRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformDataSyncService platformDataSyncService;
    private final CampusIdentityBindingRepository bindingRepository;
    private final org.springframework.beans.factory.ObjectProvider<FreeQuotaService> freeQuotaProvider;

    @Transactional
    public CampusPrincipal resolve(final CasIdentity casIdentity) {
        String account = trim(casIdentity.attribute("account"));
        String localAccount = trim(casIdentity.attribute("localAccount"));
        String lookupAccount = firstNonBlank(localAccount, account);
        if (lookupAccount == null) {
            throw authError("统一认证未返回 account/localAccount", "CAMPUS_ACCOUNT_MISSING");
        }
        String typeCode = trim(casIdentity.attribute("typeCode"));
        Set<String> roles = new LinkedHashSet<>();
        roles.add("USER");
        // Explicit temporary switch grants ADMIN to every CAS user; otherwise use the allowlist.
        if (properties.isDefaultAdmin() || isAdmin(account, localAccount)) {
            roles.add("ADMIN");
        }

        CampusIdentityBindingEntity binding = bindingRepository
                .findByIdentityProviderAndExternalSubject(properties.identityProviderUrl(), casIdentity.subject())
                .orElse(null);
        if (binding != null && !binding.isEnabled()) {
            throw authError("校园身份已被平台停用", "CAMPUS_IDENTITY_DISABLED");
        }

        // A CAS account string is not proof of ownership of an imported platform user.
        // Reuse only a binding to this CAS subject, or a namespaced user created for it.
        PlatformSystemUserEntity systemUser = binding == null ? null
                : systemUserRepository.findById(binding.getSystemUserId()).orElse(null);
        if (systemUser == null) {
            String casUserId = casUserId(properties.identityProviderUrl(), casIdentity.subject());
            // Serialize first logins across replicas. The lock and both inserts share this transaction.
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, 170644101L);
            systemUser = systemUserRepository.findByUserId(casUserId).orElse(null);
            if (systemUser == null) {
                systemUser = new PlatformSystemUserEntity();
                systemUser.setUserId(casUserId);
                systemUser.setUsername(firstNonBlank(trim(casIdentity.attribute("name")),
                        trim(casIdentity.attribute("realName")), lookupAccount));
                systemUser.setUserType(2);
                systemUser.setVerifyStatus(0); // CAS authenticates a login, not legal-name verification.
                systemUser = systemUserRepository.saveAndFlush(systemUser);
            }
        }
        UserIdentity userIdentity = platformDataSyncService
                .getUserIdentityBySystemUserId(systemUser.getId(), lookupAccount)
                .orElse(null);
        if (userIdentity == null) {
            userIdentity = new UserIdentity(systemUser.getUserId(), lookupAccount, null, null,
                    null, null, systemUser.getCompanyId(), true, systemUser.getUserType(),
                    systemUser.getId(), systemUser.getVerifyStatus());
        }

        if (properties.isUnrestrictedAccess()) {
            // This session may call paid models without a platform balance. Keep the user ID
            // for audit, but do not mark it as verified or attach a billable account.
            userIdentity = new UserIdentity(systemUser.getUserId(), lookupAccount, null, null,
                    null, null, null, false, null, systemUser.getId(), systemUser.getVerifyStatus());
        }

        Set<String> permissions = new LinkedHashSet<>();
        permissions.addAll(normalize(properties.getModelPermissions()));

        if (binding == null) {
            binding = CampusIdentityBindingEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .identityProvider(properties.identityProviderUrl())
                    .identityProtocol("CAS2")
                    .externalSubject(casIdentity.subject())
                    .enabled(true)
                    .build();
        }
        binding.setSystemUserId(systemUser.getId());
        binding.setUserAccount(lookupAccount);
        binding.setAccount(account);
        binding.setLocalAccount(localAccount);
        binding.setStaffNo(firstNonBlank(trim(casIdentity.attribute("staffNo")), localAccount));
        binding.setDisplayName(firstNonBlank(trim(casIdentity.attribute("name")),
                trim(casIdentity.attribute("realName")), systemUser.getUsername(), lookupAccount));
        // The legacy binding column is NOT NULL, while CAS typeCode is optional.
        binding.setTypeCode(typeCode == null ? "" : typeCode);
        binding.setTypeName(trim(casIdentity.attribute("typeName")));
        binding.setDepartmentCode(firstNonBlank(trim(casIdentity.attribute("deptCode")),
                trim(casIdentity.attribute("departmentCode"))));
        binding.setDepartmentName(firstNonBlank(trim(casIdentity.attribute("deptName")),
                trim(casIdentity.attribute("departmentName")), trim(casIdentity.attribute("dn"))));
        binding.setRoles(String.join(",", roles));
        binding.setUserType(userIdentity.userType());
        binding.setVerifyStatus(userIdentity.verifyStatus());
        binding.setEnterpriseId(userIdentity.enterpriseId());
        binding.setEnterpriseName(userIdentity.enterpriseName());
        binding.setCompanyId(userIdentity.companyId());
        CampusIdentityBindingEntity saved = bindingRepository.save(binding);

        FreeQuotaService quotaService = freeQuotaProvider.getIfAvailable();
        if (quotaService != null && !properties.isUnrestrictedAccess()
                && org.unreal.modelrouter.auth.security.util.RealNameAuthUtils
                .isRealNameAuthenticated(userIdentity.userType(), userIdentity.verifyStatus())) {
            quotaService.ensureQuota(userIdentity.userId(), new ArrayList<>(roles));
        }
        List<String> portals = portals(roles);
        return new CampusPrincipal(
                casIdentity.subject(), account, localAccount, saved.getDisplayName(),
                saved.getDepartmentCode(), saved.getDepartmentName(), typeCode, saved.getTypeName(),
                systemUser.getId(), systemUser.getUserId(), userIdentity.verifyStatus(),
                userIdentity.enterpriseId(), userIdentity.enterpriseName(), userIdentity.companyId(),
                new ArrayList<>(roles), new ArrayList<>(permissions), portals, userIdentity);
    }

    private String casUserId(final String provider, final String subject) {
        if (subject == null || subject.isBlank()) {
            throw authError("统一认证未返回用户标识", "CAMPUS_SUBJECT_MISSING");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((provider + "\n" + subject).getBytes(StandardCharsets.UTF_8));
            return "cas:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private boolean isAdmin(final String account, final String localAccount) {
        return properties.getAdminAccounts().stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equalsIgnoreCase(account) || value.equalsIgnoreCase(localAccount));
    }

    private Set<String> normalize(final List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.replaceFirst("^ROLE_", "").toUpperCase(Locale.ROOT))
                    .forEach(result::add);
        }
        return result;
    }

    private List<String> portals(final Set<String> roles) {
        List<String> result = new ArrayList<>();
        if (roles.contains("ADMIN")) result.add("ADMIN");
        if (roles.contains("USER")) {
            result.add("PLAYGROUND");
            result.add("PROFILE");
        }
        return result;
    }

    private String firstNonBlank(final String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String trim(final String value) {
        return value == null ? null : value.trim();
    }

    private org.unreal.modelrouter.common.exception.AuthenticationException authError(
            final String message, final String code) {
        return new org.unreal.modelrouter.common.exception.AuthenticationException(message, code);
    }
}



