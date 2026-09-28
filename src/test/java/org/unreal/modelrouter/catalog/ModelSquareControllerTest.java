package org.unreal.modelrouter.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.unreal.modelrouter.auth.campus.model.*;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.mockito.Mockito.*;

class ModelSquareControllerTest {
    final ModelSquareService service=mock(ModelSquareService.class);
    final UserIdentity user=new UserIdentity("mine","my-account",null,null,null,null,null,true,2,88L,0);
    CampusAuthentication auth() { return new CampusAuthentication(new CampusPrincipal("subject","my-account","my-account","Name",null,null,null,null,88L,"mine",0,null,null,null,List.of("USER"),List.of(),List.of(),user)); }
    WebTestClient client(Authentication auth) {
        return WebTestClient.bindToController(new ModelSquareController(service)).webFilter((ex,chain)->chain.filter(ex.mutate().principal(Mono.justOrEmpty(auth)).build())).build();
    }
    @Test void httpUsesCampusAuthenticationAndIgnoresSpoofedUser() {
        when(service.list(user)).thenReturn(List.of());
        client(auth()).get().uri("/api/model-square?userId=other&ownerId=999").exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectHeader().valueEquals("Vary","Cookie, Authorization, Jairouter_Token").expectBody().json("[]");
        verify(service).list(same(user));
    }
    @Test void httpDetailUsesCurrentIdentity() {
        when(service.detail("chat","real-model",user)).thenReturn(new ModelSquareDtos.Detail(null,List.of(),null,List.of()));
        client(auth()).get().uri("/api/model-square/detail?serviceType=chat&modelId=real-model&userId=other").exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store");
        verify(service).detail(eq("chat"),eq("real-model"),same(user));
    }
    @Test void missingOrAnonymousAuthenticationIsRejected() {
        client(null).get().uri("/api/model-square").exchange().expectStatus().isUnauthorized();
        client(new AnonymousAuthenticationToken("key","anonymousUser",AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))).get().uri("/api/model-square").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(service);
    }
}
