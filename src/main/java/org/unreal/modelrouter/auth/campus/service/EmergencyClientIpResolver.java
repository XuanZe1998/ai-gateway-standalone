package org.unreal.modelrouter.auth.campus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves the client address used by the hidden emergency login endpoint.
 * Forwarding headers are honored only when the direct TCP peer is explicitly trusted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyClientIpResolver {

    private final CampusAuthProperties properties;

    public String resolve(final ServerWebExchange exchange) {
        String peerAddress = directPeerAddress(exchange);
        if (peerAddress == null) {
            return "unknown";
        }
        boolean trustedProxy = properties.getEmergencyLogin().getTrustedProxyCidrs().stream()
                .anyMatch(cidr -> cidrContains(cidr, peerAddress));
        if (!trustedProxy) {
            return peerAddress;
        }

        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null) {
            String candidate = forwarded.split(",", 2)[0].trim();
            if (parseIpLiteral(candidate) != null) {
                return stripScopeId(candidate);
            }
        }
        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (parseIpLiteral(realIp) != null) {
            return stripScopeId(realIp.trim());
        }
        return peerAddress;
    }

    public boolean isAllowed(final String clientIp) {
        if (clientIp == null || clientIp.isBlank() || "unknown".equalsIgnoreCase(clientIp)) {
            return false;
        }
        return properties.getEmergencyLogin().getAllowedCidrs().stream()
                .anyMatch(cidr -> cidrContains(cidr, clientIp));
    }

    boolean cidrContains(final String cidr, final String address) {
        try {
            String[] parts = cidr.split("/", 2);
            byte[] network = parseIpLiteral(parts[0]);
            byte[] candidate = parseIpLiteral(address);
            if (network == null || candidate == null || network.length != candidate.length) {
                return false;
            }
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            if (prefix < 0 || prefix > network.length * 8) {
                return false;
            }
            for (int i = 0; i < network.length; i++) {
                int bits = Math.min(8, Math.max(0, prefix - i * 8));
                if (bits == 0) {
                    break;
                }
                int mask = 0xff << (8 - bits);
                if ((network[i] & mask) != (candidate[i] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            log.warn("忽略无效的应急登录 CIDR 配置: {}", cidr);
            return false;
        }
    }

    private String directPeerAddress(final ServerWebExchange exchange) {
        if (exchange == null || exchange.getRequest().getRemoteAddress() == null
                || exchange.getRequest().getRemoteAddress().getAddress() == null) {
            return null;
        }
        return stripScopeId(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
    }

    private byte[] parseIpLiteral(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = stripScopeId(value.trim());
        if (candidate.contains(":")) {
            if (!candidate.matches("[0-9A-Fa-f:.]+")) {
                return null;
            }
        } else {
            String[] octets = candidate.split("\\.", -1);
            if (octets.length != 4) {
                return null;
            }
            for (String octet : octets) {
                if (octet.isEmpty() || !octet.matches("\\d{1,3}")
                        || Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(candidate).getAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private String stripScopeId(final String address) {
        int scope = address.indexOf('%');
        return scope < 0 ? address : address.substring(0, scope);
    }
}
