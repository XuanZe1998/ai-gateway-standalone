package org.unreal.modelrouter.auth.campus.key;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*;

class DepartmentAdminSecurityTest {
    @Configuration @EnableWebFlux @EnableWebFluxSecurity @EnableReactiveMethodSecurity
    static class Config {
        @Bean DepartmentAdminService service(){var s=mock(DepartmentAdminService.class);when(s.report(any(),anyBoolean(),anyInt(),anyInt())).thenReturn(Map.of("records",List.of()));return s;}
        @Bean DepartmentAdminController controller(DepartmentAdminService service){return new DepartmentAdminController(service);}
        @Bean SecurityWebFilterChain chain(ServerHttpSecurity http){return http.authorizeExchange(a->a.anyExchange().authenticated())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(e->e.authenticationEntryPoint((exchange,error)->{exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);return exchange.getResponse().setComplete();})).build();}
    }
    @Test void administratorCanQueryIsoDatesButOrdinaryUsersCannotReadExportOrMutate() {
        try(var ctx=new AnnotationConfigApplicationContext(Config.class)){
            var client=WebTestClient.bindToApplicationContext(ctx).apply(springSecurity()).configureClient().build();
            client.get().uri("/api/admin/departments/report").exchange().expectStatus().isUnauthorized();
            var user=client.mutateWith(mockUser("campus").roles("USER"));
            user.get().uri("/api/admin/departments/report").exchange().expectStatus().isForbidden();
            user.get().uri("/api/admin/departments/export").exchange().expectStatus().isForbidden();
            user.get().uri("/api/admin/departments/options").exchange().expectStatus().isForbidden();
            user.mutateWith(csrf()).post().uri("/api/admin/departments/batches/preview").bodyValue(Map.of("departmentId","code:D1","kind","ALLOCATE","reason","test")).exchange().expectStatus().isForbidden();
            client.mutateWith(mockUser("admin").roles("ADMIN")).get().uri("/api/admin/departments/report?start=2026-10-01&end=2026-10-19&sort=tokens").exchange().expectStatus().isOk();
            verify(ctx.getBean(DepartmentAdminService.class),times(1)).report(any(),eq(false),eq(0),eq(20));
            verify(ctx.getBean(DepartmentAdminService.class),never()).csv(any(),anyBoolean());
            verify(ctx.getBean(DepartmentAdminService.class),never()).preview(any(),anyString());
        }
    }
}
