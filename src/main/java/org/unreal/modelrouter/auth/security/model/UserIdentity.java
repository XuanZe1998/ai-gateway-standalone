package org.unreal.modelrouter.auth.security.model;

import java.io.Serializable;

/**
 * 认证用户身份载体，用于从 Auth Filter 传播到计费层。
 * 存储在 ServerWebExchange attributes 和 Reactor Context 中。
 */
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
        Long systemUserId,
        Integer verifyStatus,
        boolean campusAuthenticated,
        org.unreal.modelrouter.billing.DepartmentUsageSnapshot usageSnapshot
) implements Serializable {
    public UserIdentity(String userId, String userAccount, String apiKeyId, String apiKeyName,
                        Long enterpriseId, String enterpriseName, String companyId, boolean platformUser,
                        Integer userType, Long systemUserId, Integer verifyStatus, boolean campusAuthenticated) {
        this(userId, userAccount, apiKeyId, apiKeyName, enterpriseId, enterpriseName, companyId,
                platformUser, userType, systemUserId, verifyStatus, campusAuthenticated, null);
    }
    public UserIdentity(String userId, String userAccount, String apiKeyId, String apiKeyName,
                        Long enterpriseId, String enterpriseName, String companyId, boolean platformUser,
                        Integer userType, Long systemUserId, Integer verifyStatus) {
        this(userId, userAccount, apiKeyId, apiKeyName, enterpriseId, enterpriseName, companyId,
                platformUser, userType, systemUserId, verifyStatus, false);
    }

    /** Campus login qualifies for calling; it does not alter legal-name verification or billing. */
    public UserIdentity withCampusAuthentication() {
        return new UserIdentity(userId, userAccount, apiKeyId, apiKeyName, enterpriseId, enterpriseName,
                companyId, platformUser, userType, systemUserId, verifyStatus, true, usageSnapshot);
    }
    public UserIdentity withUsageSnapshot(org.unreal.modelrouter.billing.DepartmentUsageSnapshot snapshot) {
        return new UserIdentity(userId, userAccount, apiKeyId, apiKeyName, enterpriseId, enterpriseName,
                companyId, platformUser, userType, systemUserId, verifyStatus, campusAuthenticated, snapshot);
    }
    /** 内部/未认证请求的兜底身份 */
    public static final UserIdentity SYSTEM = new UserIdentity("system", "system", null, null, null, null, null, false, null, null, null);

    /** Exchange attribute key 和 Reactor Context key */
    public static final String CONTEXT_KEY = "user.identity";
}
