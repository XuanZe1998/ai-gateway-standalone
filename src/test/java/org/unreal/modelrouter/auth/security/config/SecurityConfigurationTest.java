package org.unreal.modelrouter.auth.security.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.test.context.TestPropertySource;
import org.unreal.modelrouter.auth.security.authentication.JwtTokenValidator;
import org.unreal.modelrouter.auth.security.cache.impl.PlatformAuthCache;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.platform.sync.PlatformDataSyncService;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** SecurityConfiguration 单元测试。 */
@ExtendWith(MockitoExtension.class)
@TestPropertySource(properties = "jairouter.security.enabled=true")
class SecurityConfigurationTest {

    @Mock
    private SecurityProperties securityProperties;
    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private JwtTokenValidator jwtTokenValidator;
    @Mock
    private ApplicationContext applicationContext;
    @Mock
    private ObjectProvider<PlatformDataSyncService> platformDataSyncServiceProvider;
    @Mock
    private ObjectProvider<PlatformAuthCache> platformAuthCacheProvider;

    @Test
    void testReactiveAuthenticationManagerCreation() {
        SecurityConfiguration configuration = configuration();

        ReactiveAuthenticationManager authManager = configuration.reactiveAuthenticationManager(
                platformDataSyncServiceProvider, platformAuthCacheProvider);

        assertNotNull(authManager);
        assertInstanceOf(CustomReactiveAuthenticationManager.class, authManager);
    }

    @Test
    void testSecurityWebFilterChainCreation() {
        SecurityConfiguration configuration = configuration();
        ReactiveAuthenticationManager authManager = configuration.reactiveAuthenticationManager(
                platformDataSyncServiceProvider, platformAuthCacheProvider);

        SecurityWebFilterChain filterChain = configuration.securityWebFilterChain(
                ServerHttpSecurity.http(), authManager, mock(ServerAuthenticationConverter.class));

        assertNotNull(filterChain);
    }

    @Test
    void testSecurityPropertiesInjection() {
        assertNotNull(configuration());
    }

    private SecurityConfiguration configuration() {
        SecurityConfiguration configuration = new SecurityConfiguration(
                securityProperties, apiKeyService, applicationContext);
        configuration.setJwtTokenValidator(jwtTokenValidator);
        return configuration;
    }
}
