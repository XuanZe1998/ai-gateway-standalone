package org.unreal.modelrouter.auth.campus.service;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.unreal.modelrouter.auth.campus.config.CampusAuthProperties;
import org.unreal.modelrouter.auth.campus.model.CasIdentity;
import org.unreal.modelrouter.common.exception.AuthenticationException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** CAS 2.0 serviceValidate 客户端；使用 JVM 默认信任库和严格 XML 解析。 */
@Slf4j
@Service
public class CasProtocolClient {
    private final CampusAuthProperties properties;
    private final WebClient webClient;

    public CasProtocolClient(final CampusAuthProperties properties) {
        this.properties = properties;
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(client))
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(properties.getMaxResponseBytes()))
                .build();
    }

    public Mono<CasIdentity> validate(final String ticket, final String service) {
        if (ticket == null || ticket.isBlank()) {
            return Mono.error(error("缺少 CAS ticket", "CAS_TICKET_MISSING"));
        }
        String endpoint = properties.getCasBaseUrl().replaceAll("/+$", "") + "/serviceValidate";
        String uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("service", service)
                .queryParam("ticket", ticket)
                .build().encode().toUriString();
        return webClient.get()
                // Already encoded above. Passing a String would encode '%' again,
                // changing the exact CAS service URL (including its state query).
                .uri(java.net.URI.create(uri))
                .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(totalTimeout())
                .map(this::parse)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ex -> error("学校统一认证服务响应超时", ex, "CAS_TIMEOUT"))
                .onErrorMap(DataBufferLimitException.class,
                        ex -> error("CAS 响应超过大小限制", ex, "CAS_RESPONSE_TOO_LARGE"))
                .onErrorMap(WebClientResponseException.class,
                        ex -> error("学校统一认证服务返回异常", ex, "CAS_HTTP_ERROR"))
                .onErrorMap(WebClientRequestException.class, this::requestError);
    }


    private AuthenticationException requestError(final WebClientRequestException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SSLException) {
                return error("学校统一认证 TLS 校验失败", exception, "CAS_TLS_ERROR");
            }
            cause = cause.getCause();
        }
        return error("无法连接学校统一认证服务", exception, "CAS_CONNECTION_ERROR");
    }
    private Duration totalTimeout() {
        return properties.getConnectTimeout().plus(properties.getResponseTimeout());
    }

    CasIdentity parse(final byte[] xml) {
        if (xml == null || xml.length == 0 || xml.length > properties.getMaxResponseBytes()) {
            throw error("CAS 响应为空或超过大小限制", "CAS_RESPONSE_INVALID");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new org.xml.sax.SAXException("External entities are disabled");
            });
            var document = builder.parse(new ByteArrayInputStream(xml));
            var failures = document.getElementsByTagNameNS("*", "authenticationFailure");
            if (failures.getLength() > 0) {
                throw error("CAS ticket 无效或已使用", "CAS_TICKET_INVALID");
            }
            var successes = document.getElementsByTagNameNS("*", "authenticationSuccess");
            if (successes.getLength() != 1) {
                throw error("CAS 响应缺少认证成功节点", "CAS_RESPONSE_INVALID");
            }
            var success = successes.item(0);
            String subject = null;
            Map<String, String> attributes = new LinkedHashMap<>();
            var children = success.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                var node = children.item(i);
                if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                    continue;
                }
                String name = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
                if ("user".equals(name)) {
                    subject = clean(node.getTextContent());
                } else if ("attributes".equals(name)) {
                    var attributeNodes = node.getChildNodes();
                    for (int j = 0; j < attributeNodes.getLength(); j++) {
                        var attribute = attributeNodes.item(j);
                        if (attribute.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                            String attributeName = attribute.getLocalName() == null
                                    ? attribute.getNodeName() : attribute.getLocalName();
                            attributes.putIfAbsent(attributeName, clean(attribute.getTextContent()));
                        }
                    }
                }
            }
            if (subject == null || subject.isBlank()) {
                throw error("CAS 响应缺少用户标识", "CAS_SUBJECT_MISSING");
            }
            attributes.putIfAbsent("account", subject);
            return new CasIdentity(subject, attributes);
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("CAS XML 解析失败（响应内容未记录）: {}", exception.getClass().getSimpleName());
            throw error("CAS 响应解析失败", exception, "CAS_XML_INVALID");
        }
    }

    private String clean(final String value) {
        return value == null ? null : value.trim();
    }

    private AuthenticationException error(final String message, final String code) {
        return new AuthenticationException(message, code);
    }

    private AuthenticationException error(final String message, final Throwable cause, final String code) {
        return new AuthenticationException(message, cause, code);
    }
}


