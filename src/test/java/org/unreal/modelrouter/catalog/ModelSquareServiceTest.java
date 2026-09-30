package org.unreal.modelrouter.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.*;
import org.unreal.modelrouter.billing.freequota.FreeQuotaProperties;
import org.unreal.modelrouter.billing.pricing.BillingDimensionAliasService;
import org.unreal.modelrouter.persistence.jpa.entity.ModelSquareContentEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingDimensionAliasRepository;
import org.unreal.modelrouter.persistence.jpa.repository.ModelSquareContentRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.*;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.unreal.modelrouter.catalog.ModelSquareDtos.*;

class ModelSquareServiceTest {
    final ModelServiceRegistry registry = mock(ModelServiceRegistry.class);
    final PlatformModelRepository models = mock(PlatformModelRepository.class);
    final PlatformModelPriceTierRepository tiers = mock(PlatformModelPriceTierRepository.class);
    final ModelSquareContentRepository content = mock(ModelSquareContentRepository.class);
    final ModelPricingService pricing = mock(ModelPricingService.class);
    final DiscountCalculationService discounts = mock(DiscountCalculationService.class);
    final ObjectMapper mapper = new ObjectMapper();
    // 真实别名服务 + mock 仓库：单测不触发 @PostConstruct，缓存为空 → 维度名走默认名
    final ModelSquareService service = new ModelSquareService(registry, models, tiers, content, pricing, discounts,
            new ModelSquarePricing(new BillingDimensionAliasService(mock(BillingDimensionAliasRepository.class))),
            new FreeQuotaProperties(), mapper);
    ModelInstance instance(String id, String channel) {
        var i = new ModelInstance(); i.setName(id); i.setChannelId(channel); i.setStatus("active");
        i.setBaseUrl("https://SECRET-UPSTREAM/"); return i;
    }
    PlatformModelEntity model() {
        var m = new PlatformModelEntity(); m.setId(1L); m.setModelType("1"); m.setRealName("real-model"); m.setChannelId("secret-channel"); m.setAlias("平台名称"); m.setDescription("平台介绍"); m.setStatus("1"); m.setDeleted(false); m.setSupportTools(true); return m;
    }
    @BeforeEach void setup() {
        when(registry.getAllInstances()).thenReturn(Map.of(ServiceType.chat, List.of(instance("real-model", "secret-channel"))));
        when(models.findAll()).thenReturn(List.of(model()));
        when(discounts.calculate(any(), anyString(), any())).thenReturn(new DiscountBreakdown(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    }
    @Test void groupingUsesServiceAndRealIdAndNeverLeaksRouting() throws Exception {
        when(registry.getAllInstances()).thenReturn(Map.of(ServiceType.chat, List.of(instance("real-model", "secret-channel"),instance("real-model", "second-secret-channel")), ServiceType.embedding, List.of(instance("real-model", "third-secret-channel"))));
        var cards = service.list(UserIdentity.SYSTEM); assertEquals(2, cards.size());
        var chat = cards.stream().filter(c -> "chat".equals(c.serviceType())).findFirst().orElseThrow();
        assertEquals("平台名称", chat.displayName()); assertEquals(List.of("工具调用"), chat.tags());
        var json = mapper.writeValueAsString(service.detail("chat", "real-model", UserIdentity.SYSTEM));
        assertFalse(json.contains("SECRET-UPSTREAM")); assertFalse(json.contains("secret-channel")); assertFalse(json.contains("baseUrl"));
    }
    @Test void inactiveAndKnownRemovedModelsAreExcluded() {
        var i = instance("real-model", "secret-channel"); i.setStatus("inactive");
        when(registry.getAllInstances()).thenReturn(Map.of(ServiceType.chat,List.of(i))); assertTrue(service.list(UserIdentity.SYSTEM).isEmpty());
        i.setStatus("active"); var m=model(); m.setDeleted(true); when(models.findAll()).thenReturn(List.of(m)); assertTrue(service.list(UserIdentity.SYSTEM).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.detail("chat", "real-model", UserIdentity.SYSTEM));
    }
    @Test void localRuntimeModelHasHonestFallbackAndNoInferredCapabilities() {
        when(models.findAll()).thenReturn(List.of()); var card=service.list(UserIdentity.SYSTEM).get(0);
        assertEquals("real-model", card.displayName()); assertEquals("模型介绍待完善", card.description()); assertTrue(card.tags().isEmpty()); assertEquals("UNKNOWN",card.priceStatus());
    }
    @Test void channelPricesMergeOnlyWhenIdenticalAndIdentityIsPreserved() {
        when(registry.getAllInstances()).thenReturn(Map.of(ServiceType.chat,List.of(instance("real-model","secret-channel"),instance("real-model","second-secret-channel"))));
        var p = new ModelPricingService.ModelPricing("real-model", "secret-channel", new BigDecimal("0.000001"),new BigDecimal("0.000002"));
        when(pricing.getPrice(anyString(),any())).thenReturn(p);
        var identity = new UserIdentity("mine","mine",null,null,null,null,null,true,2,88L,0);
        assertEquals(1,service.detail("chat","real-model",identity).schemes().size()); verify(discounts).calculate(same(identity),eq("real-model"),eq("secret-channel"));
        when(pricing.getPrice("real-model","second-secret-channel")).thenReturn(new ModelPricingService.ModelPricing("real-model","second-secret-channel",new BigDecimal("0.000003"),new BigDecimal("0.000004")));
        var detail=service.detail("chat","real-model",identity); assertEquals(2,detail.schemes().size()); assertEquals("MULTIPLE",detail.model().priceStatus()); assertTrue(detail.model().priceSummary().isEmpty());
    }
    @Test void saveOverridesAndResetUseOnlyPresentationFields() {
        when(content.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var result=service.save(new Content("chat","real-model"," 新名称 ","<b>纯文本</b>",List.of("自定义")),"actor");
        assertEquals("新名称",result.model().displayName()); assertEquals("real-model",result.model().modelId()); assertEquals("<b>纯文本</b>",result.model().description()); assertEquals("actor",result.updatedBy()); assertNotNull(result.updatedAt());
        var capture=org.mockito.ArgumentCaptor.forClass(ModelSquareContentEntity.class); verify(content).saveAndFlush(capture.capture());
        assertEquals("chat:real-model",capture.getValue().getContentKey()); assertEquals("[\"自定义\"]",capture.getValue().getTags());
        when(content.findAll()).thenReturn(List.of(capture.getValue())); assertEquals("新名称",service.list(UserIdentity.SYSTEM).get(0).displayName());
        service.reset("chat","real-model"); verify(content).deleteById("chat:real-model"); verify(models,never()).save(any());
    }
    @Test void limitsAndNonRunningModelsAreRejected() {
        assertThrows(ResponseStatusException.class,()->service.save(new Content("chat","real-model","x".repeat(101),null,List.of()),"actor"));
        assertThrows(ResponseStatusException.class,()->service.save(new Content("chat","real-model",null,"x".repeat(2001),List.of()),"actor"));
        assertThrows(ResponseStatusException.class,()->service.save(new Content("chat","real-model",null,null,List.of("x".repeat(17))),"actor"));
        assertThrows(ResponseStatusException.class,()->service.save(new Content("chat","real-model",null,null,List.of("1","2","3","4","5","6","7")),"actor"));
        assertThrows(ResponseStatusException.class,()->service.save(new Content("chat","unknown",null,null,List.of()),"actor")); verify(content,never()).saveAndFlush(any());
    }
    @Test void everyServiceUsesItsRealEndpointAndUploadContentType() {
        var map=new EnumMap<ServiceType,List<ModelInstance>>(ServiceType.class); for(var type:ServiceType.values())map.put(type,List.of(instance("local-model",null)));
        when(registry.getAllInstances()).thenReturn(map); when(models.findAll()).thenReturn(List.of());
        assertEquals("/v1/rerank",service.detail("rerank","local-model",UserIdentity.SYSTEM).access().path());
        assertEquals("multipart/form-data",service.detail("stt","local-model",UserIdentity.SYSTEM).access().contentType());
        assertEquals("multipart/form-data",service.detail("imgEdit","local-model",UserIdentity.SYSTEM).access().contentType());
        assertEquals("/v1/videos/generations/task/{taskId}",service.detail("vidGen","local-model",UserIdentity.SYSTEM).access().queryPath());
        assertEquals(8,service.list(UserIdentity.SYSTEM).size());
    }
}
