package org.unreal.modelrouter.auth.campus.model;

import org.unreal.modelrouter.auth.security.model.UserIdentity;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.List;

/** 存入 WebSession 的最小化校园用户身份，不保存身份证、手机号等敏感属性。 */
public record CampusPrincipal(
        String subject,
        String account,
        String localAccount,
        String displayName,
        String departmentCode,
        String departmentName,
        String typeCode,
        String typeName,
        Long systemUserId,
        String platformUserId,
        Integer verifyStatus,
        Long enterpriseId,
        String enterpriseName,
        String companyId,
        List<String> roles,
        List<String> permissions,
        List<String> portals,
        UserIdentity userIdentity
) implements Principal, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public CampusPrincipal {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        portals = portals == null ? List.of() : List.copyOf(portals);
    }

    @Override
    public String getName() {
        return localAccount != null && !localAccount.isBlank() ? localAccount : account;
    }
}
