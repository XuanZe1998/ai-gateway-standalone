package org.unreal.modelrouter.auth.campus.key;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

@RestController
@RequestMapping("/api/admin/departments")
@PreAuthorize("hasRole('ADMIN')")
public class DepartmentAdminController {
    private final DepartmentAdminService service;
    public DepartmentAdminController(DepartmentAdminService service){this.service=service;}
    @GetMapping("/options")
    public Mono<RouterResponse<Map<String,Object>>> options(){return async(service::directoryOptions);}
    @GetMapping("/report")
    public Mono<RouterResponse<Map<String,Object>>> report(@ModelAttribute DepartmentAdminService.Filter filter,
            @RequestParam(defaultValue="false") boolean members,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return async(()->service.report(filter,members,page,size));
    }
    @GetMapping("/detail")
    public Mono<RouterResponse<Map<String,Object>>> detail(@RequestParam String department){return async(()->service.department(department));}
    @GetMapping("/users/{owner}")
    public Mono<RouterResponse<Map<String,Object>>> user(@PathVariable long owner,@ModelAttribute DepartmentAdminService.Filter filter,
            @RequestParam(defaultValue="0") int billPage,@RequestParam(defaultValue="20") int billSize){return async(()->service.user(owner,filter,billPage,billSize));}
    @GetMapping("/alerts")
    public Mono<RouterResponse<List<Map<String,Object>>>> alerts(@RequestParam(required=false) String department){return async(()->service.alerts(department));}
    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> export(@ModelAttribute DepartmentAdminService.Filter filter,@RequestParam(defaultValue="false") boolean members){
        return Mono.fromCallable(()->ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=department-usage.csv")
                .body(service.csv(filter,members).getBytes(StandardCharsets.UTF_8))).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/batches/preview")
    public Mono<RouterResponse<Map<String,Object>>> preview(Authentication auth,@RequestBody DepartmentAdminService.BatchRequest request){return async(()->service.preview(request,auth.getName()));}
    @PostMapping("/batches/{id}/commit")
    public Mono<RouterResponse<Map<String,Object>>> commit(Authentication auth,@PathVariable String id){return async(()->{
        try{return service.commit(id,auth.getName());}catch(RuntimeException error){service.recordFailure(id,auth.getName(),error.getMessage());throw error;}
    });}
    public record Reconcile(String reason) {}
    @PostMapping("/reservations/{id}/retry")
    public Mono<RouterResponse<Map<String,Object>>> retry(Authentication auth,@PathVariable String id,@RequestBody Reconcile body){return async(()->service.retrySettlement(id,body.reason(),auth.getName()));}
    private <T> Mono<RouterResponse<T>> async(Callable<T> action){return Mono.fromCallable(()->RouterResponse.success(action.call())).subscribeOn(Schedulers.boundedElastic());}
}
