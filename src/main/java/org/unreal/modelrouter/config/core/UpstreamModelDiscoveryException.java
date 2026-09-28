package org.unreal.modelrouter.config.core;

/**
 * 上游模型发现请求或响应异常。
 */
public class UpstreamModelDiscoveryException extends RuntimeException {

    public UpstreamModelDiscoveryException(final String message) {
        super(message);
    }

    public UpstreamModelDiscoveryException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
