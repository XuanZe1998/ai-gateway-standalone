package org.unreal.modelrouter.billing.pricing;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.persistence.jpa.entity.BillingDimensionAliasEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingDimensionAliasRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 计费维度显示别名服务（仅展示层）。
 *
 * <p>管理员可为计费维度自定义显示名（如把「普通输入（未命中缓存）」改为「输入 Token」），
 * 底层六维计费字段与计费逻辑完全不变。别名按计费组（text/voice/image）全局生效，
 * 定价管理后台与模型广场共用同一套名称口径。
 *
 * <p>dimension_key 与前端 PricingStrategyKey 保持一致：
 * normalPrice / output / cacheHit / cacheCreate / cacheHitExplicit / thinking / discount。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingDimensionAliasService {

    /** serviceType → 计费组（与前端 STRATEGY_GROUP 分组一致） */
    public static final Map<String, String> GROUP_OF_SERVICE_TYPE = Map.of(
            "chat", "text", "embedding", "text", "rerank", "text",
            "tts", "voice", "stt", "voice",
            "imgGen", "image", "imgEdit", "image");

    /** 合法 dimension_key */
    public static final Set<String> DIMENSION_KEYS = Set.of(
            "normalPrice", "output", "cacheHit", "cacheCreate", "cacheHitExplicit", "thinking", "discount");

    private static final int MAX_NAME_LENGTH = 60;

    private final BillingDimensionAliasRepository repository;

    /** groupKey → (dimensionKey → display_name)；volatile 全量替换，读侧无锁 */
    private volatile Map<String, Map<String, String>> cache = Map.of();

    @PostConstruct
    public void refresh() {
        Map<String, Map<String, String>> fresh = new HashMap<>();
        for (BillingDimensionAliasEntity e : repository.findAll()) {
            if (e.getGroupKey() == null || e.getDimensionKey() == null) continue;
            fresh.computeIfAbsent(e.getGroupKey(), k -> new HashMap<>())
                    .put(e.getDimensionKey(), e.getDisplayName());
        }
        Map<String, Map<String, String>> immutable = new HashMap<>();
        fresh.forEach((group, dims) -> immutable.put(group, Map.copyOf(dims)));
        this.cache = Map.copyOf(immutable);
        log.info("计费维度别名缓存已加载: {} 组", immutable.size());
    }

    /** serviceType → 计费组（未知类型归入 text 组，与其默认策略组一致） */
    public String groupKeyOf(String serviceType) {
        return GROUP_OF_SERVICE_TYPE.getOrDefault(serviceType == null ? "" : serviceType, "text");
    }

    /** 某服务类型所在组的全部别名（无别名返回空 Map） */
    public Map<String, String> aliasesOfServiceType(String serviceType) {
        return cache.getOrDefault(groupKeyOf(serviceType), Map.of());
    }

    /** 取显示名：优先别名，未配置用调用方默认名 */
    public String displayName(String serviceType, String dimensionKey, String defaultName) {
        return aliasesOfServiceType(serviceType).getOrDefault(dimensionKey, defaultName);
    }

    /**
     * 整组替换别名（定价管理保存时同事务调用）。
     * 草稿中 value 为 null/空白 = 恢复默认名（该条删除）；至少一个维度有效时组内其余别名同步补齐。
     */
    @Transactional
    public void replaceAliases(String groupKey, Map<String, String> aliases) {
        if (groupKey == null || !GROUP_OF_SERVICE_TYPE.containsValue(groupKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的计费组: " + groupKey);
        }
        repository.deleteByGroupKey(groupKey);
        if (aliases != null) {
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                String key = entry.getKey();
                if (!DIMENSION_KEYS.contains(key)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的计费维度: " + key);
                }
                String name = entry.getValue() == null ? "" : entry.getValue().trim();
                if (name.isEmpty()) continue; // 空 = 恢复默认名
                if (name.length() > MAX_NAME_LENGTH) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "维度名称过长（≤" + MAX_NAME_LENGTH + "）: " + name);
                }
                BillingDimensionAliasEntity e = new BillingDimensionAliasEntity();
                e.setGroupKey(groupKey);
                e.setDimensionKey(key);
                e.setDisplayName(name);
                e.setUpdatedAt(LocalDateTime.now());
                repository.save(e);
            }
        }
        refresh(); // 事务内刷新（同事务连接可见），与 pricingService.refreshPricing 的调用模式保持一致
    }
}
