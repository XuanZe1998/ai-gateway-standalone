package org.unreal.modelrouter.catalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.unreal.modelrouter.router.controller.UniversalController;
import org.unreal.modelrouter.common.dto.ImageEditDTO;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ModelSquareMultipartTest {
    @Test void multipartUploadBindsFilesAndParametersWithoutUpstreamCall(){
        var ctrl=spy(new UniversalController(null,null,null,null,null));var captured=new AtomicReference<ImageEditDTO.Request>();
        doAnswer(i->{captured.set(i.getArgument(1));return Mono.just(ResponseEntity.ok().build());}).when(ctrl).imageEdits(any(),any(),any());
        var body=new MultipartBodyBuilder();body.part("model","model-id");body.part("prompt","edit");body.part("n","2");body.part("stream","false");body.part("image",new ByteArrayResource(new byte[]{1,2,3}){public String getFilename(){return "sample.png";}});
        WebTestClient.bindToController(ctrl).build().post().uri("/api/v1/images/edits").header("Authorization","Bearer YOUR_KEY").contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(body.build()).exchange().expectStatus().isOk();
        assertEquals("model-id",captured.get().model());assertEquals(1,captured.get().image().size());assertEquals(2,captured.get().n());assertFalse(captured.get().stream());
    }
    @Test void missingFileAndInvalidIntegerAreBadRequests(){
        var ctrl=spy(new UniversalController(null,null,null,null,null));var client=WebTestClient.bindToController(ctrl).build();
        var body=new MultipartBodyBuilder();body.part("model","model-id");body.part("prompt","edit");
        client.post().uri("/api/v1/images/edits").contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(body.build()).exchange().expectStatus().isBadRequest();
        body.part("image",new ByteArrayResource(new byte[]{1}){public String getFilename(){return "sample.png";}});body.part("n","invalid");
        client.post().uri("/api/v1/images/edits").contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(body.build()).exchange().expectStatus().isBadRequest();verify(ctrl,never()).imageEdits(any(),any(),any());
    }
}
