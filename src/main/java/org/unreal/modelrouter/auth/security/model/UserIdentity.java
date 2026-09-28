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
        Integer verifyStatus
) implements Serializable {
    /** 内部/未认证请求的兜底身份 */
    public static final UserIdentity SYSTEM = new UserIdentity("system", "system", null, null, null, null, null, false, null, null, null);

    /** Exchange attribute key 和 Reactor Context key */
    public static final String CONTEXT_KEY = "user.identity";
}
