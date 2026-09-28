package org.unreal.modelrouter.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.unreal.modelrouter.catalog.ModelSquareDtos.*;

@RestController
@RequestMapping("/api/admin/model-square/content")
@PreAuthorize("hasRole('ADMIN')")
public class ModelSquareAdminController {
    private final ModelSquareService service;
    public ModelSquareAdminController(ModelSquareService service) { this.service = service; }
    @GetMapping
    public Mono<ResponseEntity<List<ContentView>>> list(Authentication auth) {
        ModelSquareController.identity(auth);
        return ModelSquareController.response(service::contentList);
    }
    @PutMapping
    public Mono<ResponseEntity<ContentView>> save(Authentication auth, @RequestBody Content body) {
        ModelSquareController.identity(auth);
        return ModelSquareController.response(() -> service.save(body, auth.getName()));
    }
    @DeleteMapping
    public Mono<ResponseEntity<Boolean>> reset(Authentication auth, @RequestParam String serviceType, @RequestParam String modelId) {
        ModelSquareController.identity(auth);
        return ModelSquareController.response(() -> { service.reset(serviceType, modelId); return true; });
    }
}
