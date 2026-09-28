package org.unreal.modelrouter.auth.teacher.model;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.unreal.modelrouter.auth.security.model.UserIdentity;

import java.util.List;

/**
 * 设备绑定的教师短期访问令牌认证对象。
 */
public class TeacherAccessAuthentication extends AbstractAuthenticationToken {

    private final String token;
    private final TeacherRequestProof requestProof;
    private final Object principal;

    public TeacherAccessAuthentication(final String token, final TeacherRequestProof requestProof) {
        super(List.of());
        this.token = token;
        this.requestProof = requestProof;
        this.principal = null;
        setAuthenticated(false);
    }

    public TeacherAccessAuthentication(final String token,
                                       final TeacherRequestProof requestProof,
                                       final UserIdentity identity,
                                       final List<String> permissions) {
        super(permissions.stream()
                .map(String::toUpperCase)
                .map(permission -> new SimpleGrantedAuthority("ROLE_" + permission))
                .toList());
        this.token = token;
        this.requestProof = requestProof;
        this.principal = identity.userId();
        setDetails(identity);
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    public TeacherRequestProof getRequestProof() {
        return requestProof;
    }
}
