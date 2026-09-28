package org.unreal.modelrouter.auth.campus.key;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Database-backed, account-owned keys. The old config-store keys are intentionally never read here. */
@Service
public class CampusGatewayKeyService {
    private static final String PREFIX = "gw2_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public CampusGatewayKeyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record KeyView(String keyId, long ownerId, String platformUserId, String name,
                          String status, LocalDateTime createdAt, LocalDateTime updatedAt,
                          LocalDateTime expiresAt) {}
    public record IssuedKey(KeyView key, String secret) {}

    public static boolean isNewKey(String secret) { return secret != null && secret.startsWith(PREFIX); }

    public KeyView authenticate(String secret) {
        if (!isNewKey(secret)) return null;
        List<KeyView> keys = jdbc.query("SELECT key_id, owner_id, platform_user_id, name, status, created_at, updated_at, expires_at "
                + "FROM campus_gateway_key WHERE key_hash = ? AND status = 'ACTIVE' "
                + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)",
                (rs, i) -> view(rs), hash(secret));
        return keys.isEmpty() ? null : keys.get(0);
    }

    public List<KeyView> list(Long ownerId) {
        String sql = "SELECT key_id, owner_id, platform_user_id, name, status, created_at, updated_at, expires_at "
                + "FROM campus_gateway_key WHERE status <> 'REVOKED' "
                + (ownerId == null ? "" : "AND owner_id = ? ") + "ORDER BY created_at DESC";
        return ownerId == null ? jdbc.query(sql, (rs, i) -> view(rs))
                : jdbc.query(sql, (rs, i) -> view(rs), ownerId);
    }

    public int limit(long ownerId) {
        if (ownerId < 0) throw new IllegalArgumentException("用户 ID 不能为负数");
        Integer override = jdbc.query("SELECT max_keys FROM campus_gateway_key_limit WHERE owner_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, ownerId);
        if (override != null) return override;
        return jdbc.queryForObject("SELECT max_keys FROM campus_gateway_key_limit WHERE owner_id = 0", Integer.class);
    }

    public long count(long ownerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM campus_gateway_key WHERE owner_id = ? AND status <> 'REVOKED'",
                Long.class, ownerId);
    }

    @Transactional
    public IssuedKey create(long ownerId, String platformUserId, String name, boolean bypassLimit) {
        if (ownerId <= 0) throw new IllegalArgumentException("校园用户 ID 必须大于 0");
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Key 名称须为 1-120 字符");
        if (platformUserId == null || platformUserId.isBlank()) throw new IllegalArgumentException("校园账户未关联计费身份");
        // Lock the shared default row, including when a user-specific override is absent. Serializes all
        // self-service allocations with limit edits and prevents cross-node check/insert races.
        jdbc.queryForObject("SELECT max_keys FROM campus_gateway_key_limit WHERE owner_id = 0 FOR UPDATE", Integer.class);
        if (!bypassLimit && count(ownerId) >= limit(ownerId)) throw new IllegalStateException("Key 数量已达到管理员设置的上限");
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String keyId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO campus_gateway_key (key_id, owner_id, platform_user_id, key_hash, name, status) "
                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')", keyId, ownerId, platformUserId, hash(secret), name.trim());
        return new IssuedKey(get(keyId, ownerId), secret);
    }

    @Transactional
    public IssuedKey rotate(String keyId, long ownerId) {
        KeyView old = getForUpdate(keyId, ownerId);
        if ("REVOKED".equals(old.status())) throw new IllegalStateException("Key 已撤销");
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE campus_gateway_key SET key_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE key_id = ?",
                hash(secret), keyId);
        return new IssuedKey(get(keyId, ownerId), secret);
    }

    @Transactional
    public KeyView status(String keyId, long ownerId, String status) {
        if (!List.of("ACTIVE", "DISABLED", "REVOKED").contains(status)) throw new IllegalArgumentException("无效状态");
        KeyView old = getForUpdate(keyId, ownerId);
        if ("REVOKED".equals(old.status())) throw new IllegalStateException("已撤销的 Key 不能恢复");
        jdbc.update("UPDATE campus_gateway_key SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE key_id = ?",
                status, keyId);
        return get(keyId, ownerId);
    }

    @Transactional
    public void setLimit(long ownerId, int maxKeys) {
        if (ownerId < 0) throw new IllegalArgumentException("用户 ID 不能为负数");
        if (maxKeys < 0 || maxKeys > 10000) throw new IllegalArgumentException("上限须在 0-10000 之间");
        jdbc.queryForObject("SELECT max_keys FROM campus_gateway_key_limit WHERE owner_id = 0 FOR UPDATE", Integer.class);
        jdbc.update("INSERT INTO campus_gateway_key_limit (owner_id, max_keys) VALUES (?, ?) "
                + "ON CONFLICT (owner_id) DO UPDATE SET max_keys = EXCLUDED.max_keys", ownerId, maxKeys);
    }

    @Transactional
    public void clearLimit(long ownerId) {
        if (ownerId <= 0) throw new IllegalArgumentException("只能清除个人覆盖上限");
        jdbc.queryForObject("SELECT max_keys FROM campus_gateway_key_limit WHERE owner_id = 0 FOR UPDATE", Integer.class);
        jdbc.update("DELETE FROM campus_gateway_key_limit WHERE owner_id = ?", ownerId);
    }

    private KeyView getForUpdate(String id, long owner) {
        return queryOne(id, owner, " FOR UPDATE");
    }
    private KeyView get(String id, long owner) { return queryOne(id, owner, ""); }
    private KeyView queryOne(String id, long owner, String suffix) {
        List<KeyView> results = jdbc.query("SELECT key_id, owner_id, platform_user_id, name, status, "
                        + "created_at, updated_at, expires_at FROM campus_gateway_key WHERE key_id = ? AND owner_id = ?" + suffix,
                (rs, i) -> view(rs), id, owner);
        if (results.isEmpty()) throw new IllegalArgumentException("Key 不存在或不属于当前账户");
        return results.get(0);
    }
    private static KeyView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        var expiration = rs.getTimestamp("expires_at");
        return new KeyView(rs.getString("key_id"), rs.getLong("owner_id"), rs.getString("platform_user_id"),
                rs.getString("name"), rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(), expiration == null ? null : expiration.toLocalDateTime());
    }
    private static String hash(String secret) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
