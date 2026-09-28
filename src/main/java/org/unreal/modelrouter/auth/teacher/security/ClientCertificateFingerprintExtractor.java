package org.unreal.modelrouter.auth.teacher.security;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * 从当前 TLS 连接提取已验证客户端证书的指纹。
 */
@Component
public class ClientCertificateFingerprintExtractor {

    public String extract(final ServerWebExchange exchange) {
        if (exchange.getRequest().getSslInfo() == null) {
            return null;
        }
        Certificate[] certificates = exchange.getRequest().getSslInfo().getPeerCertificates();
        if (certificates == null || certificates.length == 0) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificates[0].getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算客户端证书指纹", exception);
        }
    }
}
