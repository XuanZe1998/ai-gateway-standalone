package org.unreal.modelrouter.auth.campus.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.springframework.web.server.session.WebSessionManager;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import reactor.core.publisher.Mono;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Correlates a CAS service ticket with its WebSession for back-channel single logout. */
@Service
@RequiredArgsConstructor
public class CasSingleLogoutService {
    private static final String REDIS_KEY_PREFIX = "jairouter:cas:slo:";
    private static final int MAX_LOGOUT_REQUEST_BYTES = 16_384;

    private final WebSessionManager webSessionManager;
    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    private final Environment environment;
    private final CampusAuthProperties properties;
    private final ConcurrentMap<String, SessionReference> localSessions = new ConcurrentHashMap<>();
    private final AtomicInteger registrations = new AtomicInteger();

    public Mono<Void> register(final String ticket, final WebSession session) {
        String key = ticketKey(ticket);
        Duration ttl = properties.getSessionTimeout();
        if (usesRedis()) {
            return redis().opsForValue().set(key, session.getId(), ttl).then();
        }
        long expiresAt = System.currentTimeMillis() + ttl.toMillis();
        localSessions.put(key, new SessionReference(session.getId(), expiresAt));
        if (registrations.incrementAndGet() % 256 == 0) {
            long now = System.currentTimeMillis();
            localSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        }
        return Mono.empty();
    }

    public Mono<Void> invalidate(final String logoutRequest) {
        String key = ticketKey(parseSessionIndex(logoutRequest));
        Mono<String> sessionId = usesRedis()
                ? redis().opsForValue().get(key)
                : Mono.justOrEmpty(localSessions.get(key))
                        .filter(reference -> reference.expiresAt() > System.currentTimeMillis())
                        .map(SessionReference::sessionId);
        return sessionId.flatMap(id -> sessionStore().retrieveSession(id)
                        .flatMap(WebSession::invalidate)
                        .thenReturn(id))
                .then(Mono.defer(() -> remove(key)));
    }

    public Mono<Void> removeTicket(final String ticket) {
        return remove(ticketKey(ticket));
    }

    private Mono<Void> remove(final String key) {
        if (usesRedis()) {
            return redis().delete(key).then();
        }
        localSessions.remove(key);
        return Mono.empty();
    }

    private org.springframework.web.server.session.WebSessionStore sessionStore() {
        if (webSessionManager instanceof DefaultWebSessionManager manager) {
            return manager.getSessionStore();
        }
        throw new IllegalStateException("CAS 单点注销需要可按 ID 查找的 WebSessionStore");
    }

    private boolean usesRedis() {
        return "redis".equalsIgnoreCase(environment.getProperty("spring.session.store-type", "none"));
    }

    private ReactiveStringRedisTemplate redis() {
        ReactiveStringRedisTemplate template = redisProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("Redis 会话模式需要 ReactiveStringRedisTemplate 才能支持 CAS 单点注销");
        }
        return template;
    }

    static String parseSessionIndex(final String request) {
        if (request == null || request.isBlank()
                || request.getBytes(StandardCharsets.UTF_8).length > MAX_LOGOUT_REQUEST_BYTES) {
            throw new IllegalArgumentException("CAS 注销报文为空或过大");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new org.xml.sax.SAXException("External entities are disabled");
            });
            var document = builder.parse(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)));
            if (!"LogoutRequest".equals(document.getDocumentElement().getLocalName())) {
                throw new IllegalArgumentException("不是 CAS 注销请求");
            }
            NodeList indexes = document.getElementsByTagNameNS("*", "SessionIndex");
            if (indexes.getLength() != 1) {
                throw new IllegalArgumentException("CAS 注销请求缺少唯一的 SessionIndex");
            }
            String ticket = indexes.item(0).getTextContent().trim();
            if (ticket.isEmpty() || ticket.length() > 512) {
                throw new IllegalArgumentException("CAS 注销请求的 SessionIndex 无效");
            }
            return ticket;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("CAS 注销报文格式无效", exception);
        }
    }

    private String ticketKey(final String ticket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.UTF_8));
            return REDIS_KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private record SessionReference(String sessionId, long expiresAt) { }
}
