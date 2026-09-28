package org.unreal.modelrouter.auth.campus.key;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/** Global administration deliberately bypasses self-service limits; all campus ADMINs have access. */
@RestController
@RequestMapping("/api/admin/campus-keys")
@PreAuthorize("hasRole('ADMIN')")
public class CampusGatewayKeyAdminController {
    private final CampusGatewayKeyService keys;
    public CampusGatewayKeyAdminController(CampusGatewayKeyService keys) { this.keys = keys; }
    public record LimitRequest(int maxKeys) {}
    public record AdminCreate(long ownerId, String platformUserId, String name) {}

    @GetMapping
    public Mono<List<CampusGatewayKeyService.KeyView>> list() {
        return Mono.fromCallable(() -> keys.list(null)).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping("/limits/{ownerId}")
    public Mono<Map<String, Object>> getLimit(@PathVariable long ownerId) {
        return Mono.fromCallable(() -> Map.<String, Object>of("ownerId", ownerId,
                "maxKeys", keys.limit(ownerId))).subscribeOn(Schedulers.boundedElastic());
    }
    @DeleteMapping("/limits/{ownerId}")
    public Mono<Map<String, Object>> clearLimit(@PathVariable long ownerId) {
        return Mono.fromCallable(() -> { keys.clearLimit(ownerId);
            return Map.<String, Object>of("ownerId", ownerId, "maxKeys", keys.limit(ownerId)); })
                .subscribeOn(Schedulers.boundedElastic());
    }
    @PutMapping("/limits/{ownerId}")
    public Mono<Map<String, Object>> limit(@PathVariable long ownerId, @RequestBody LimitRequest body) {
        return Mono.fromCallable(() -> { keys.setLimit(ownerId, body.maxKeys());
            return Map.<String, Object>of("ownerId", ownerId, "maxKeys", body.maxKeys()); })
                .subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping
    public Mono<CampusGatewayKeyService.IssuedKey> create(@RequestBody AdminCreate body) {
        return Mono.fromCallable(() -> keys.create(body.ownerId(), body.platformUserId(), body.name(), true))
                .subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/{ownerId}/{id}/rotate")
    public Mono<CampusGatewayKeyService.IssuedKey> rotate(@PathVariable long ownerId, @PathVariable String id) {
        return Mono.fromCallable(() -> keys.rotate(id, ownerId))
                .subscribeOn(Schedulers.boundedElastic());
    }
    @PatchMapping("/{ownerId}/{id}")
    public Mono<CampusGatewayKeyService.KeyView> status(@PathVariable long ownerId, @PathVariable String id,
            @RequestBody CampusSelfServiceController.ChangeStatus body) {
        return Mono.fromCallable(() -> keys.status(id, ownerId, body.status()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
