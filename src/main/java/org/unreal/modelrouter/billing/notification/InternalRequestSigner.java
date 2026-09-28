package org.unreal.modelrouter.billing.notification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 服务间内部请求签名工具。
 * 使用 HMAC-SHA256 对请求体进行签名，防止未授权调用和请求篡改。
 *
 * <p>算力平台侧用相同的 secret 和算法验签。
 */
public final class InternalRequestSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalRequestSigner() {
    }

    /**
     * 计算签名。
     *
     * @param secret    共享密钥
     * @param timestamp 请求时间戳（毫秒）
     * @param body      请求体 JSON 字符串
     * @return HMAC-SHA256 hex 签名
     */
    public static String sign(String secret, String timestamp, String body) {
        try {
            String message = timestamp + body;
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }
}
