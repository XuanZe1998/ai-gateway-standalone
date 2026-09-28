package org.unreal.modelrouter.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.DiscountCalculationService;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.billing.freequota.FreeQuotaProperties;
import org.unreal.modelrouter.persistence.jpa.entity.ModelSquareContentEntity;
import org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelSquareContentRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelRepository;
import org.unreal.modelrouter.persistence.jpa.repository.platform.PlatformModelPriceTierRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties.ModelInstance;
import org.unreal.modelrouter.router.model.ModelServiceRegistry;
import org.unreal.modelrouter.router.model.ModelServiceRegistry.ServiceType;
import java.time.LocalDateTime;
import java.util.*;
import static org.unreal.modelrouter.catalog.ModelSquareDtos.*;

@Service
public class ModelSquareService {
    private final ModelServiceRegistry registry;
    private final PlatformModelRepository models;
    private final PlatformModelPriceTierRepository tiers;
    private final ModelSquareContentRepository contents;
    private final ModelPricingService pricing;
    private final DiscountCalculationService discounts;
    private final ModelSquarePricing presenter;
    private final FreeQuotaProperties quota;
    private final ObjectMapper mapper;
    public ModelSquareService(ModelServiceRegistry registry, PlatformModelRepository models,
                              PlatformModelPriceTierRepository tiers, ModelSquareContentRepository contents,
                              ModelPricingService pricing, DiscountCalculationService discounts,
                              ModelSquarePricing presenter, FreeQuotaProperties quota, ObjectMapper mapper) {
        this.registry = registry; this.models = models; this.tiers = tiers; this.contents = contents;
        this.pricing = pricing; this.discounts = discounts; this.presenter = presenter;
        this.quota = quota; this.mapper = mapper;
    }
    private record Entry(ServiceType service, String model, List<ModelInstance> instances,
                         List<PlatformModelEntity> metadata) {}
    private String key(String type, String id) { return type + ":" + id; }
    private List<Entry> entries() {
        var source = models.findAll();
        List<Entry> result = new ArrayList<>();
        registry.getAllInstances().forEach((type, instances) -> {
            Map<String, List<ModelInstance>> grouped = new TreeMap<>();
            for (var instance : instances) {
                if (instance.getName() == null || instance.getName().isBlank()
                        || !"active".equalsIgnoreCase(instance.getStatus())) continue;
                var metadata = source.stream().filter(m -> matches(m, type, instance.getName(), instance.getChannelId())).toList();
                // A known platform model must still be online; local-only models remain visible.
                if (!metadata.isEmpty() && metadata.stream().noneMatch(this::online)) continue;
                grouped.computeIfAbsent(instance.getName(), ignored -> new ArrayList<>()).add(instance);
            }
            grouped.forEach((id, group) -> result.add(new Entry(type, id, group, source.stream()
                    .filter(m -> online(m) && matches(m, type, id, null)).sorted(Comparator.comparing(
                            PlatformModelEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList())));
        });
        return result;
    }
    private boolean matches(PlatformModelEntity m, ServiceType type, String id, String channel) {
        String name = m.getRealName() == null ? String.valueOf(m.getId()) : m.getRealName();
        if (!id.equals(name)) return false;
        String kind = m.getModelType() == null ? "chat" : m.getModelType().trim();
        String service = switch (kind) {
            case "1", "chat", "chat-completion" -> "chat";
            case "2", "img-gen", "image-generation", "image" -> "imgGen";
            case "3", "vid-gen", "vidGen" -> "vidGen";
            case "4", "tts", "text-to-speech", "audio" -> "tts";
            case "5", "embedding", "embeddings" -> "embedding";
            case "6", "rerank", "re-rank" -> "rerank";
            case "stt", "speech-to-text" -> "stt";
            case "img-edit", "image-editing", "imgEdit" -> "imgEdit";
            default -> "chat";
        };
        return type.name().equals(service) && (channel == null || Objects.equals(channel, m.getChannelId()));
    }
    private boolean online(PlatformModelEntity m) { return Boolean.FALSE.equals(m.getDeleted()) && "1".equals(m.getStatus()); }
    private Entry find(String type, String id) {
        if (type == null || id == null || id.isBlank() || id.length() > 255) throw bad("模型标识无效");
        return entries().stream().filter(e -> e.service().name().equals(type) && e.model().equals(id))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型已下架或不可接入"));
    }
    public List<Card> list(UserIdentity identity) {
        Map<String, ModelSquareContentEntity> overrides = new HashMap<>();
        contents.findAll().forEach(c -> overrides.put(c.getContentKey(), c));
        return entries().stream().map(e -> detail(e, identity, overrides.get(key(e.service().name(), e.model()))).model())
                .sorted(Comparator.comparing(Card::displayName).thenComparing(Card::modelId).thenComparing(Card::serviceType)).toList();
    }
    public Detail detail(String type, String id, UserIdentity identity) {
        Entry e = find(type, id);
        return detail(e, identity, contents.findById(key(type, id)).orElse(null));
    }
    private Detail detail(Entry e, UserIdentity identity, ModelSquareContentEntity override) {
        List<Scheme> schemes = new ArrayList<>();
        Set<String> channels = new LinkedHashSet<>();
        for (var instance : e.instances()) {
            if (!channels.add(instance.getChannelId())) continue;
            var p = pricing.getPrice(e.model(), instance.getChannelId());
            var raw = e.metadata().stream().filter(m -> p != null && Objects.equals(m.getId(), p.getModelId()))
                    .findFirst().orElseGet(() -> e.metadata().stream().filter(m -> Objects.equals(m.getChannelId(), p == null ? instance.getChannelId() : p.getChannelId())).findFirst().orElse(null));
            var rawTiers = p != null && Integer.valueOf(2).equals(p.getBillingMode()) && p.getModelId() != null
                    ? tiers.findByModelIdOrderByTierOrderAsc(p.getModelId()) : List.<org.unreal.modelrouter.persistence.jpa.entity.platform.PlatformModelPriceTierEntity>of();
            var scheme = presenter.present(e.service().name(), p,
                    discounts.calculate(identity, e.model(), instance.getChannelId()), raw, rawTiers);
            if (!schemes.contains(scheme)) schemes.add(scheme);
        }
        var labeled = new ArrayList<Scheme>();
        for (int i = 0; i < schemes.size(); i++) {
            var s = schemes.get(i);
            labeled.add(new Scheme("收费方案 " + (i + 1), s.mode(), s.configured(), s.discounts(), s.lines(), s.notes()));
        }
        String name = defaultName(e), description = defaultDescription(e);
        List<String> tags = defaultTags(e);
        if (override != null) {
            if (override.getDisplayName() != null && !override.getDisplayName().isBlank()) name = override.getDisplayName();
            if (override.getDescription() != null && !override.getDescription().isBlank()) description = override.getDescription();
            var custom = decodeTags(override.getTags());
            if (!custom.isEmpty()) tags = custom;
        }
        var vendors = new TreeSet<String>();
        e.instances().forEach(i -> { if (i.getVendor() != null && !i.getVendor().isBlank()) vendors.add(i.getVendor()); });
        e.metadata().forEach(m -> { if (m.getVendor() != null && !m.getVendor().isBlank()) vendors.add(m.getVendor()); });
        boolean incomplete = labeled.stream().anyMatch(s -> !s.configured());
        String priceStatus = labeled.size() > 1 ? "MULTIPLE" : incomplete || labeled.isEmpty() ? "UNKNOWN" : "CONFIGURED";
        List<PriceLine> summary = List.of();
        if (labeled.size() == 1 && !incomplete && labeled.get(0).mode().equals("整体计费")) {
            summary = labeled.get(0).lines().subList(0, Math.min(2, labeled.get(0).lines().size()));
        } else if (labeled.size() == 1 && !incomplete && labeled.get(0).lines().size() == 1) summary = labeled.get(0).lines();
        var card = new Card(e.service().name(), e.model(), name, description, tags, List.copyOf(vendors),
                labeled.size(), priceStatus, summary, quota.isEnabled() && quota.isFreeQuotaServiceType(e.service().name()), override != null);
        return new Detail(card, List.copyOf(labeled), access(e.service()), List.of(
                "费用按当前登录账户适用规则展示；真正调用仍需有效 Key、实名认证及可用额度或余额。",
                "免费额度仅适用于标记的服务；付费单价不代表本次请求一定扣费。企业余额可能为共享余额。",
                "展示为当前配置快照，多方案由实际路由决定，最终以实际用量及账单为准。"));
    }
    private String defaultName(Entry e) {
        return e.metadata().stream().map(PlatformModelEntity::getAlias).filter(v -> v != null && !v.isBlank()).findFirst().orElse(e.model());
    }
    private String defaultDescription(Entry e) {
        return e.metadata().stream().map(PlatformModelEntity::getDescription).filter(v -> v != null && !v.isBlank()).findFirst().orElse("模型介绍待完善");
    }
    private List<String> defaultTags(Entry e) {
        List<String> tags = new ArrayList<>();
        if (e.metadata().stream().anyMatch(m -> Boolean.TRUE.equals(m.getSupportThinking()))) tags.add("思考能力");
        if (e.metadata().stream().anyMatch(m -> Boolean.TRUE.equals(m.getSupportTools()))) tags.add("工具调用");
        return List.copyOf(tags);
    }
    private Access access(ServiceType type) {
        String path = switch (type) {
            case chat -> "/v1/chat/completions"; case embedding -> "/v1/embeddings"; case rerank -> "/v1/rerank";
            case tts -> "/v1/audio/speech"; case stt -> "/v1/audio/transcriptions";
            case imgGen -> "/v1/images/generations"; case imgEdit -> "/v1/images/edits"; case vidGen -> "/v1/videos/generations";
        };
        return new Access("POST", path, type == ServiceType.stt || type == ServiceType.imgEdit ? "multipart/form-data" : "application/json",
                type == ServiceType.vidGen ? "/v1/videos/generations/task/{taskId}" : null,
                type == ServiceType.chat ? "对话使用 OpenAI 兼容端点；其他服务按各自接口格式接入。"
                        : "请按该服务的参数格式调用，不承诺全部服务与第三方 OpenAI 客户端完全兼容。");
    }
    public List<ContentView> contentList() {
        Map<String, ModelSquareContentEntity> overrides = new HashMap<>();
        contents.findAll().forEach(c -> overrides.put(c.getContentKey(), c));
        return entries().stream().map(e -> contentView(e, overrides.get(key(e.service().name(), e.model()))))
                .sorted(Comparator.comparing(v -> v.model().displayName())).toList();
    }
    private ContentView contentView(Entry e, ModelSquareContentEntity c) {
        return new ContentView(detail(e, UserIdentity.SYSTEM, c).model(), defaultName(e), defaultDescription(e), defaultTags(e),
                c == null ? null : new Content(c.getServiceType(), c.getModelId(), c.getDisplayName(), c.getDescription(), decodeTags(c.getTags())),
                c == null ? null : c.getUpdatedAt(), c == null ? null : c.getUpdatedBy());
    }
    @Transactional
    public ContentView save(Content request, String actor) {
        Entry entry = find(request.serviceType(), request.modelId());
        String name = optional(request.displayName(), 100, "展示名称"), description = optional(request.description(), 2000, "简介");
        var tags = request.tags() == null ? List.<String>of() : request.tags();
        if (tags.size() > 6) throw bad("标签最多 6 个");
        var normalized = new LinkedHashSet<String>();
        for (var tag : tags) { var value = optional(tag, 16, "标签"); if (value == null) throw bad("标签不能为空"); normalized.add(value); }
        var content = new ModelSquareContentEntity();
        content.setContentKey(key(request.serviceType(), request.modelId()));
        content.setServiceType(request.serviceType()); content.setModelId(request.modelId());
        content.setDisplayName(name); content.setDescription(description);
        try { content.setTags(mapper.writeValueAsString(normalized)); }
        catch (Exception ex) { throw new IllegalStateException("标签序列化失败", ex); }
        content.setUpdatedAt(LocalDateTime.now()); content.setUpdatedBy(actor);
        return contentView(entry, contents.saveAndFlush(content));
    }
    @Transactional
    public void reset(String type, String id) { find(type, id); contents.deleteById(key(type, id)); }
    private String optional(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > max) throw bad(label + "超过长度限制");
        return trimmed;
    }
    private List<String> decodeTags(String json) {
        try { return mapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("模型标签数据格式错误", e); }
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
