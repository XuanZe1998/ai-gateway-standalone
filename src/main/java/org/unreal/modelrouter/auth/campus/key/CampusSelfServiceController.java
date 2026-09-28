package org.unreal.modelrouter.auth.campus.key;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.unreal.modelrouter.auth.campus.model.CampusAuthentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.campus.model.CampusPrincipal;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.auth.security.util.RealNameAuthUtils;
import org.unreal.modelrouter.billing.freequota.FreeQuotaRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformAccountBalanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Session-derived self-service API. A stateless key/JWT cannot impersonate a campus account here. */
@RestController
@RequestMapping("/api/me")
public class CampusSelfServiceController {
    private final CampusGatewayKeyService keys;
    private final FreeQuotaRepository quotas;
    private final PlatformAccountBalanceRepository balances;
    private final JdbcTemplate jdbc;

    public CampusSelfServiceController(CampusGatewayKeyService keys, FreeQuotaRepository quotas,
                                       PlatformAccountBalanceRepository balances, JdbcTemplate jdbc) {
        this.keys = keys; this.quotas = quotas; this.balances = balances; this.jdbc = jdbc;
    }
    public record CreateKey(String name) {}
    public record ChangeStatus(String status) {}

    private CampusPrincipal require(Authentication authentication) {
        // CampusPrincipal implements Principal, so WebFlux's built-in resolver otherwise injects
        // the Authentication itself before @AuthenticationPrincipal can unwrap it.
        if (!(authentication instanceof CampusAuthentication campus) || !campus.isAuthenticated()
                || campus.getPrincipal().systemUserId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请使用校园账户登录");
        }
        return campus.getPrincipal();
    }
    private <T> Mono<T> db(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(() -> {
            try { return action.call(); }
            catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
            catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/profile")
    public Mono<Map<String, Object>> profile(Authentication authentication) {
        var user = require(authentication);
        return Mono.just(Map.<String, Object>of(
                "ownerId", user.systemUserId(),
                "platformUserId", user.platformUserId(),
                "account", user.getName(),
                "displayName", user.displayName() == null ? "" : user.displayName(),
                "department", user.departmentName() == null ? "" : user.departmentName(),
                "verified", user.userIdentity() != null && RealNameAuthUtils.isRealNameAuthenticated(
                        user.userIdentity().userType(), user.userIdentity().verifyStatus())));
    }

    @GetMapping("/keys")
    public Mono<Map<String, Object>> list(Authentication authentication) {
        var user = require(authentication);
        return db(() -> Map.of("keys", keys.list(user.systemUserId()), "limit", keys.limit(user.systemUserId()),
                "count", keys.count(user.systemUserId())));
    }
    @PostMapping("/keys")
    public Mono<CampusGatewayKeyService.IssuedKey> create(Authentication authentication,
                                                           @RequestBody CreateKey request) {
        var user = require(authentication);
        return db(() -> keys.create(user.systemUserId(), user.platformUserId(), request.name(), false));
    }
    @PostMapping("/keys/{id}/rotate")
    public Mono<CampusGatewayKeyService.IssuedKey> rotate(Authentication authentication,
                                                           @PathVariable String id) {
        var user = require(authentication);
        return db(() -> keys.rotate(id, user.systemUserId()));
    }
    @PatchMapping("/keys/{id}")
    public Mono<CampusGatewayKeyService.KeyView> status(Authentication authentication,
                                                          @PathVariable String id, @RequestBody ChangeStatus body) {
        var user = require(authentication);
        return db(() -> keys.status(id, user.systemUserId(), body.status()));
    }
    @GetMapping("/balance")
    public Mono<Map<String, Object>> balance(Authentication authentication) {
        var user = require(authentication);
        return db(() -> {
            UserIdentity identity = user.userIdentity();
            var quota = quotas.findByUserIdAndDeletedFalse(user.platformUserId()).orElse(null);
            boolean shared = identity != null && Integer.valueOf(1).equals(identity.userType());
            String accountId = shared ? identity.companyId() : user.platformUserId();
            int accountType = shared ? 1 : 2;
            var balance = accountId == null ? null : balances.findByAccountIdAndAccountTypeAndDeletedFalse(accountId, accountType).orElse(null);
            return Map.<String, Object>of(
                    "verified", identity != null && RealNameAuthUtils.isRealNameAuthenticated(identity.userType(), identity.verifyStatus()),
                    "quota", quota == null ? Map.of("exists", false) : Map.of("exists", true,
                            "total", quota.getTotalQuota(), "used", quota.getUsedQuota(),
                            "remaining", quota.getRemainingQuota(), "periodEnd", quota.getPeriodEnd().toString()),
                    "paid", balance == null ? Map.of("exists", false, "shared", shared) : Map.of(
                            "exists", true, "shared", shared, "balance", balance.getBalance()));
        });
    }
    @GetMapping("/usage")
    public Mono<Map<String, Object>> usage(Authentication authentication,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String model, @RequestParam(required = false) String keyId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var user = require(authentication);
        if (page < 0 || page > 100000 || size < 1 || size > 100 || (from != null && to != null && from.isAfter(to)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页或日期范围无效");
        return db(() -> {
            // A key filter is accepted only if the key belongs to this campus account.
            // Revoked keys remain reportable, but cannot be managed or authenticated.
            if (keyId != null && !keyId.isBlank()) {
                Long owned = jdbc.queryForObject("SELECT COUNT(*) FROM campus_gateway_key WHERE key_id = ? AND owner_id = ?",
                        Long.class, keyId, user.systemUserId());
                if (owned == 0) throw new IllegalArgumentException("Key 不属于当前账户");
            }
            StringBuilder where = new StringBuilder(" FROM ai_billing_record WHERE user_id = ? AND is_deleted = false");
            List<Object> args = new ArrayList<>(); args.add(user.platformUserId());
            if (from != null) { where.append(" AND started_at >= ?"); args.add(from.atStartOfDay()); }
            if (to != null) { where.append(" AND started_at < ?"); args.add(to.plusDays(1).atStartOfDay()); }
            if (model != null && !model.isBlank()) { where.append(" AND model_name = ?"); args.add(model); }
            if (keyId != null && !keyId.isBlank()) { where.append(" AND api_key_id = ?"); args.add(keyId); }
            var summary = jdbc.queryForMap("SELECT COUNT(*) AS requests, COALESCE(SUM(total_tokens),0) AS tokens, "
                    + "COALESCE(SUM(total_cost),0) AS cost" + where, args.toArray());
            List<Object> paging = new ArrayList<>(args); paging.add(size); paging.add(page * size);
            var rows = jdbc.queryForList("SELECT id, model_name AS model, service_type AS service, api_key_id AS key_id, "
                    + "total_tokens AS tokens, total_cost AS cost, is_success AS success, started_at AS time"
                    + where + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?", paging.toArray());
            return Map.<String, Object>of("summary", summary, "records", rows, "page", page, "size", size);
        });
    }
}
