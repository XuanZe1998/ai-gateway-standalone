package org.unreal.modelrouter.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.unreal.modelrouter.auth.security.config.SecurityConfiguration;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.service.ApiKeyService;
import org.unreal.modelrouter.common.exceptionhandler.ReactiveGlobalExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

class ModelSquareSecurityTest {
    @Configuration @EnableWebFlux @EnableWebFluxSecurity @EnableReactiveMethodSecurity
    static class Config {
        @Bean ModelSquareService service() { var s=mock(ModelSquareService.class);when(s.list(any())).thenReturn(List.of());when(s.contentList()).thenReturn(List.of());return s; }
        @Bean ModelSquareController controller(ModelSquareService s){return new ModelSquareController(s);}
        @Bean ModelSquareAdminController admin(ModelSquareService s){return new ModelSquareAdminController(s);}
        @Bean ReactiveGlobalExceptionHandler errors(){var h=mock(ReactiveGlobalExceptionHandler.class);when(h.handle(any(),any())).thenAnswer(i->{org.springframework.web.server.ServerWebExchange ex=i.getArgument(0);ex.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);return ex.getResponse().setComplete();});return h;}
        @Bean SecurityWebFilterChain chain(ServerHttpSecurity http,ApplicationContext ctx){var props=new SecurityProperties();props.getApiKey().setEnabled(false);props.getJwt().setEnabled(false);return new SecurityConfiguration(props,mock(ApiKeyService.class),ctx).securityWebFilterChain(http,Mono::just,ex->Mono.empty());}
    }
    @Test void realSecurityChainAllowsOrdinaryUserButProtectsAdminContent(){
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)){
            var client=WebTestClient.bindToApplicationContext(ctx).apply(springSecurity()).configureClient().build();
            client.get().uri("/api/model-square").exchange().expectStatus().isUnauthorized();
            client.mutateWith(mockUser("normal").roles("USER")).get().uri("/api/model-square").exchange().expectStatus().isOk();
            client.mutateWith(mockUser("normal").roles("USER")).get().uri("/api/admin/model-square/content").exchange().expectStatus().isForbidden();
            client.mutateWith(mockUser("admin").roles("ADMIN")).get().uri("/api/admin/model-square/content").exchange().expectStatus().isOk();
        }
    }
    @Test void methodSecurityAlsoRejectsNonAdministrator(){
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)){
            var auth=new UsernamePasswordAuthenticationToken("normal","n/a",AuthorityUtils.createAuthorityList("ROLE_USER"));
            var controller=ctx.getBean(ModelSquareAdminController.class);
            StepVerifier.create(controller.list(auth).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))).expectError(AccessDeniedException.class).verify();
            verify(ctx.getBean(ModelSquareService.class),never()).contentList();
        }
    }
}
