package org.unreal.modelrouter.auth.campus.key;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.unreal.modelrouter.auth.campus.model.CampusAuthentication;
import reactor.core.publisher.Mono;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.freequota.FreeQuotaRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformAccountBalanceRepository;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CampusSelfServiceControllerTest {
    private final CampusGatewayKeyService keys = mock(CampusGatewayKeyService.class);
    private final FreeQuotaRepository quotas = mock(FreeQuotaRepository.class);
    private final PlatformAccountBalanceRepository balances = mock(PlatformAccountBalanceRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CampusSelfServiceController controller = new CampusSelfServiceController(keys, quotas, balances, jdbc);

    private CampusPrincipal principal() {
        var identity = new UserIdentity("cas:mine", "my-account", null, null, null, null,
                null, true, 2, 88L, 0);
        return new CampusPrincipal("cas-subject", "my-account", "my-account", "My Name",
                null, null, null, null, 88L, "cas:mine", 0, null, null, null,
                List.of("USER"), List.of(), List.of(), identity);
    }

    @Test
    void keyOwnerIsAlwaysDerivedFromCampusSession() {
        when(keys.list(88L)).thenReturn(List.of());
        when(keys.limit(88L)).thenReturn(5);
        var response = controller.list(authentication()).block();
        assertNotNull(response);
        assertEquals(5, response.get("limit"));
        verify(keys).list(88L);
        verify(keys, never()).list(null);
    }

    @Test
    void keyCreationUsesOnlyTheSessionOwnerAndBillingUser() {
        var issued = new CampusGatewayKeyService.IssuedKey(null, "only-once");
        when(keys.create(88L, "cas:mine", "my app", false)).thenReturn(issued);
        assertSame(issued, controller.create(authentication(), new CampusSelfServiceController.CreateKey("my app")).block());
        verify(keys).create(88L, "cas:mine", "my app", false);
    }

    @Test
    void usageRejectsAnotherAccountsKeyBeforeQueryingBillingRecords() {
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM campus_gateway_key WHERE key_id = ? AND owner_id = ?"),
                eq(Long.class), eq("foreign"), eq(88L))).thenReturn(0L);
        var error = assertThrows(ResponseStatusException.class,
                () -> controller.usage(authentication(), null, null, null, "foreign", 0, 20).block());
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(jdbc, never()).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void statelessCredentialCannotAccessSelfService() {
        var error = assertThrows(ResponseStatusException.class, () -> controller.list(null));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(keys, quotas, balances);
    }

    @Test
    void unverifiedAndMissingBalanceAreReportedAsMissingNotFabricated() {
        when(quotas.findByUserIdAndDeletedFalse("cas:mine")).thenReturn(Optional.empty());
        when(balances.findByAccountIdAndAccountTypeAndDeletedFalse("cas:mine", 2)).thenReturn(Optional.empty());
        var response = controller.balance(authentication()).block();
        assertNotNull(response);
        assertEquals(false, response.get("verified"));
        assertEquals(false, ((java.util.Map<?, ?>) response.get("quota")).get("exists"));
        assertEquals(false, ((java.util.Map<?, ?>) response.get("paid")).get("exists"));
    }

    private CampusAuthentication authentication() {
        return new CampusAuthentication(principal());
    }

    private WebTestClient client(Authentication authentication) {
        return WebTestClient.bindToController(controller)
                .webFilter((exchange, chain) -> chain.filter(exchange.mutate()
                        .principal(Mono.justOrEmpty(authentication)).build()))
                .build();
    }

    @Test
    void httpProfileUnwrapsTheCampusAuthentication() {
        client(authentication()).get().uri("/api/me/profile").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.ownerId").isEqualTo(88)
                .jsonPath("$.account").isEqualTo("my-account")
                .jsonPath("$.verified").isEqualTo(false);
    }

    @Test
    void httpKeyListUsesTheSessionAndIgnoresOtherOwnerParameters() {
        when(keys.list(88L)).thenReturn(List.of());
        when(keys.limit(88L)).thenReturn(5);
        client(authentication()).get().uri("/api/me/keys?ownerId=999&userId=other").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.limit").isEqualTo(5)
                .jsonPath("$.count").isEqualTo(0)
                .jsonPath("$.keys").isEmpty();
        verify(keys).list(88L);
        verify(keys, never()).list(999L);
    }

    @Test
    void httpBalanceReportsUnverifiedMissingAccountsWithoutServerError() {
        when(quotas.findByUserIdAndDeletedFalse("cas:mine")).thenReturn(Optional.empty());
        when(balances.findByAccountIdAndAccountTypeAndDeletedFalse("cas:mine", 2)).thenReturn(Optional.empty());
        client(authentication()).get().uri("/api/me/balance").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.verified").isEqualTo(false)
                .jsonPath("$.quota.exists").isEqualTo(false)
                .jsonPath("$.paid.exists").isEqualTo(false);
    }

    @Test
    void httpUsageBindsDatesAndQueriesTheSessionBillingUser() {
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("requests", 0L, "tokens", 0L, "cost", 0));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        client(authentication()).get().uri("/api/me/usage?from=2026-09-01&to=2026-09-28&userId=other").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.summary.requests").isEqualTo(0)
                .jsonPath("$.records").isEmpty();
        verify(jdbc).queryForMap(anyString(), eq("cas:mine"),
                eq(java.time.LocalDate.of(2026, 9, 1).atStartOfDay()),
                eq(java.time.LocalDate.of(2026, 9, 29).atStartOfDay()));
    }

    @Test
    void httpCreateRotateAndDisableKeysUseTheSessionOwner() {
        var key = new CampusGatewayKeyService.KeyView("mine", 88L, "cas:mine", "my app", "ACTIVE",
                null, null, null);
        var issued = new CampusGatewayKeyService.IssuedKey(key, "gw2_test-only");
        when(keys.create(88L, "cas:mine", "my app", false)).thenReturn(issued);
        when(keys.rotate("mine", 88L)).thenReturn(issued);
        when(keys.status("mine", 88L, "DISABLED")).thenReturn(key);
        var client = client(authentication());
        client.post().uri("/api/me/keys").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "my app", "ownerId", 999))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.secret").isEqualTo("gw2_test-only");
        client.post().uri("/api/me/keys/mine/rotate").exchange().expectStatus().isOk();
        client.patch().uri("/api/me/keys/mine").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", "DISABLED")).exchange().expectStatus().isOk();
        verify(keys).create(88L, "cas:mine", "my app", false);
        verify(keys).rotate("mine", 88L);
        verify(keys).status("mine", 88L, "DISABLED");
    }

    @Test
    void httpSelfServiceRejectsMissingOrStatelessAuthentication() {
        var stateless = new UsernamePasswordAuthenticationToken(principal(), "unused", List.of());
        for (var client : List.of(client(null), client(stateless))) {
            for (String path : List.of("profile", "keys", "balance", "usage")) {
                client.get().uri("/api/me/" + path).exchange().expectStatus().isForbidden();
            }
        }
        verifyNoInteractions(keys, quotas, balances, jdbc);
    }

    @Test
    void httpSelfServiceRejectsAnUnauthenticatedCampusToken() {
        var authentication = authentication();
        authentication.setAuthenticated(false);
        client(authentication).get().uri("/api/me/keys").exchange().expectStatus().isForbidden();
        verifyNoInteractions(keys, quotas, balances, jdbc);
    }
}
