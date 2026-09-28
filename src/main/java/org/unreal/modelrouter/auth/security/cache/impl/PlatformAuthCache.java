package org.unreal.modelrouter.auth.security.cache.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.cache.CacheMetrics;
import org.unreal.modelrouter.auth.security.config.properties.CacheConfig;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.auth.security.model.UserIdentity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 算力平台 API Key 认证结果缓存。
 * 缓存 plaintext apiKey → UserIdentity 的映射，避免每次请求都全表扫描 ai_api_key。
 *
 * <p>正缓存（key 有效）：缓存完整的 UserIdentity（含企业信息），TTL 默认 60 秒，
 * 且不会超过 API Key 自身的 expire_time。
 * 负缓存（key 无效）：缓存 identity=null 的条目，TTL 默认 30 秒，防止恶意请求反复扫表。
 *
 * <p>遵循项目现有 {@link InMemoryApiKeyCache} 模式：ConcurrentHashMap + TTL + ScheduledExecutorService 定期清理。
 */
@Slf4j
@Component
public class PlatformAuthCache {

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "platform-auth-cache-cleanup");
        t.setDaemon(true);
        return t;
    });

    private final CacheMetrics cacheMetrics;
    private final Duration positiveTtl;
    private final Duration negativeTtl;
    private final int maxSize;

    /**
     * 缓存条目。
     * identity != null → 有效 key 的认证结果
     * identity == null → 负缓存（key 无效）
     *
     * @param keyExpireTime 算力平台 API Key 本身的 expire_time，用于在缓存 TTL 到达前
     *                      提前感知 key 过期（避免 key 被改到过去后仍被缓存放行）
     */
    public record CacheEntry(UserIdentity identity, Instant expiresAt, LocalDateTime keyExpireTime) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt) || isKeyExpired();
        }

        /** 是否为负缓存条目（key 无效） */
        public boolean isNegative() {
            return identity == null;
        }

        /** API Key 本身的过期时间是否已到 */
        public boolean isKeyExpired() {
            if (keyExpireTime == null) {
                return false;
            }
            return LocalDateTime.now().isAfter(keyExpireTime);
        }
    }

    public PlatformAuthCache(@Autowired(required = false) final CacheMetrics cacheMetrics,
                             final SecurityProperties securityProperties) {
        this.cacheMetrics = cacheMetrics;
        CacheConfig.InMemoryConfig inMemory = securityProperties.getCache().getInMemory();
        this.positiveTtl = Duration.ofSeconds(inMemory.getPlatformAuthPositiveTtlSeconds());
        this.negativeTtl = Duration.ofSeconds(inMemory.getPlatformAuthNegativeTtlSeconds());
        this.maxSize = inMemory.getMaxSize();

        long cleanupMinutes = inMemory.getCleanupIntervalMinutes();
        scheduler.scheduleAtFixedRate(this::cleanupExpiredEntries, cleanupMinutes, cleanupMinutes, TimeUnit.MINUTES);
        log.info("平台认证缓存初始化完成: positiveTtl={}s, negativeTtl={}s, maxSize={}, cleanup={}min",
                positiveTtl.toSeconds(), negativeTtl.toSeconds(), maxSize, cleanupMinutes);
    }

    /**
     * 查询缓存。
     *
     * @return null = 缓存未命中（需查 DB）；非 null = 缓存命中，检查 {@link CacheEntry#identity()} 判正/负
     */
    public CacheEntry get(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }

        try {
            CacheEntry entry = cache.get(apiKey);
            if (entry == null) {
                if (cacheMetrics != null) {
                    cacheMetrics.recordCacheMiss();
                }
                log.debug("平台认证缓存未命中: {}...", apiKeySubstring(apiKey));
                return null;
            }

            if (entry.isExpired()) {
                cache.remove(apiKey);
                if (cacheMetrics != null) {
                    cacheMetrics.recordCacheEviction();
                    cacheMetrics.recordCacheMiss();
                }
                if (entry.isKeyExpired()) {
                    log.debug("平台认证缓存因 API Key 过期而失效: {}...", apiKeySubstring(apiKey));
                } else {
                    log.debug("平台认证缓存已过期: {}...", apiKeySubstring(apiKey));
                }
                return null;
            }

            if (cacheMetrics != null) {
                cacheMetrics.recordCacheHit();
            }
            log.debug("平台认证缓存命中: {}... ({})", apiKeySubstring(apiKey),
                    entry.isNegative() ? "负缓存" : "正缓存");
            return entry;
        } catch (Exception e) {
            log.warn("查询平台认证缓存异常: {}", apiKeySubstring(apiKey), e);
            return null;
        }
    }

    /**
     * 缓存有效的认证结果（不带 key 过期时间，向后兼容）。
     */
    public void putValid(String apiKey, UserIdentity identity) {
        putValid(apiKey, identity, null);
    }

    /**
     * 缓存有效的认证结果，并根据 API Key 的过期时间裁剪缓存 TTL。
     * 这样即使配置的正缓存 TTL 很长，key 也不会在缓存里“活过”其 expire_time。
     */
    public void putValid(String apiKey, UserIdentity identity, LocalDateTime keyExpireTime) {
        if (apiKey == null || apiKey.isEmpty() || identity == null) {
            return;
        }
        Instant effectiveExpiresAt = computeEffectiveExpiresAt(keyExpireTime, positiveTtl);
        put(apiKey, new CacheEntry(identity, effectiveExpiresAt, keyExpireTime));
    }

    /**
     * 缓存无效 key 的负缓存条目。
     */
    public void putInvalid(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return;
        }
        put(apiKey, new CacheEntry(null, Instant.now().plus(negativeTtl), null));
    }

    /**
     * 驱逐指定 key 的缓存。
     */
    public void evict(String apiKey) {
        CacheEntry removed = cache.remove(apiKey);
        if (removed != null && cacheMetrics != null) {
            cacheMetrics.recordCacheEviction();
            log.debug("驱逐平台认证缓存: {}...", apiKeySubstring(apiKey));
        }
    }

    /**
     * 清空全部缓存。
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        if (cacheMetrics != null) {
            cacheMetrics.updateCacheSize(0);
        }
        log.info("清空平台认证缓存，共清理 {} 个条目", size);
    }

    /**
     * 获取当前缓存大小。
     */
    public int size() {
        return cache.size();
    }

    private void put(String apiKey, CacheEntry entry) {
        enforceMaxSize();
        CacheEntry previous = cache.put(apiKey, entry);
        if (cacheMetrics != null) {
            cacheMetrics.recordCacheWrite();
            if (previous == null) {
                cacheMetrics.incrementCacheSize();
            }
        }
        log.debug("写入平台认证缓存: {}... (TTL={}s, type={})",
                apiKeySubstring(apiKey),
                entry.isNegative() ? negativeTtl.toSeconds() : positiveTtl.toSeconds(),
                entry.isNegative() ? "负" : "正");
    }

    /**
     * 超过 maxSize 时清理过期条目，若仍超限则淘汰最早写入的 10%。
     */
    private void enforceMaxSize() {
        if (cache.size() < maxSize) {
            return;
        }
        // 先清理过期条目
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        // 若仍超限，淘汰最早过期的 10%
        if (cache.size() >= maxSize) {
            int toRemove = Math.max(1, maxSize / 10);
            cache.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e -> e.getValue().expiresAt()))
                    .limit(toRemove)
                    .forEach(e -> cache.remove(e.getKey()));
            log.info("平台认证缓存达上限(maxSize={})，淘汰 {} 个最早过期条目", maxSize, toRemove);
        }
    }

    /**
     * 定期清理过期条目。
     */
    private void cleanupExpiredEntries() {
        try {
            int initialSize = cache.size();
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            int finalSize = cache.size();
            int removed = initialSize - finalSize;
            if (removed > 0) {
                if (cacheMetrics != null) {
                    cacheMetrics.updateCacheSize(finalSize);
                }
                log.debug("清理过期平台认证缓存: {} 个条目", removed);
            }
        } catch (Exception e) {
            log.error("清理平台认证缓存异常", e);
        }
    }

    private static String apiKeySubstring(String apiKey) {
        return apiKey.substring(0, Math.min(8, apiKey.length()));
    }

    /**
     * 根据 API Key 自身的过期时间计算缓存条目的有效过期时间。
     * 如果 key 的过期时间早于配置 TTL，则以 key 过期时间为准。
     */
    private static Instant computeEffectiveExpiresAt(LocalDateTime keyExpireTime, Duration positiveTtl) {
        Instant ttlExpiresAt = Instant.now().plus(positiveTtl);
        if (keyExpireTime == null) {
            return ttlExpiresAt;
        }
        Instant keyExpiresAt = keyExpireTime.atZone(ZoneId.systemDefault()).toInstant();
        return keyExpiresAt.isBefore(ttlExpiresAt) ? keyExpiresAt : ttlExpiresAt;
    }
}
