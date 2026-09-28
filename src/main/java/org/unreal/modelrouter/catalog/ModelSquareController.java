package org.unreal.modelrouter.catalog;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.campus.model.CampusAuthentication;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.List;
import static org.unreal.modelrouter.catalog.ModelSquareDtos.*;

@RestController
@RequestMapping("/api/model-square")
public class ModelSquareController {
    private final ModelSquareService service;
    public ModelSquareController(ModelSquareService service) { this.service = service; }
    // Authentication, not @AuthenticationPrincipal Principal: WebFlux's built-in resolver wins.
    static UserIdentity identity(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (auth instanceof CampusAuthentication campus) return campus.getPrincipal().userIdentity();
        return auth.getDetails() instanceof UserIdentity user ? user : UserIdentity.SYSTEM;
    }
    static <T> Mono<ResponseEntity<T>> response(java.util.concurrent.Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic())
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Vary", "Cookie, Authorization, Jairouter_Token").body(body));
    }
    @GetMapping
    public Mono<ResponseEntity<List<Card>>> list(Authentication auth) {
        var identity = identity(auth);
        return response(() -> service.list(identity));
    }
    @GetMapping("/detail")
    public Mono<ResponseEntity<Detail>> detail(Authentication auth, @RequestParam String serviceType, @RequestParam String modelId) {
        var identity = identity(auth);
        return response(() -> service.detail(serviceType, modelId, identity));
    }
}
