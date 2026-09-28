package org.unreal.modelrouter.platform.sync;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES 加解密工具 — 与算力平台 {@code AesCryptoUtil} 保持一致。
 *
 * <p>算力平台的 {@code ai_api_key.key_hash} 字段实际存储的是 AES 加密后的密文（而非哈希），
 * 本工具用于解密该字段以完成 API Key 认证比对。
 *
 * <p>算法：AES/ECB/PKCS5Padding，密钥与算力平台一致。
 */
@Slf4j
public final class PlatformAesCryptoUtil {

    private static final String SECRET_KEY = "aiMall2026!@#$%^";

    private PlatformAesCryptoUtil() {
    }

    private static SecretKeySpec getKey() {
        byte[] keyBytes = new byte[16];
        byte[] src = SECRET_KEY.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, keyBytes, 0, Math.min(src.length, 16));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * AES 加密
     *
     * @param plainText 明文
     * @return Base64 编码的密文
     */
    public static String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES encrypt failed", e);
        }
    }

    /**
     * AES 解密
     *
     * @param encryptedText Base64 编码的密文
     * @return 明文
     */
    public static String decrypt(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, getKey());
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decrypt failed", e);
        }
    }
}
