package org.unreal.modelrouter.auth.campus.model;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.util.stream.Stream;

/** 已完成本地账号映射和授权的校园 Session Authentication。 */
public class CampusAuthentication extends AbstractAuthenticationToken {
    @Serial
    private static final long serialVersionUID = 1L;

    private final CampusPrincipal principal;

    public CampusAuthentication(final CampusPrincipal principal) {
        super(Stream.concat(principal.roles().stream(), principal.permissions().stream())
                .map(CampusAuthentication::authorityName)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList());
        this.principal = principal;
        setDetails(principal.userIdentity());
        setAuthenticated(true);
    }

    private static String authorityName(final String value) {
        return value.startsWith("ROLE_") ? value : "ROLE_" + value;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public CampusPrincipal getPrincipal() {
        return principal;
    }
}
