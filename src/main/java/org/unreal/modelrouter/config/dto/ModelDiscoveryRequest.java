package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 上游模型发现请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDiscoveryRequest {

    /**
     * 上游基础地址，也可以是完整的推理接口地址。
     */
    private String baseUrl;

    /**
     * 可选的模型列表地址。为空时根据 baseUrl 自动推导 /v1/models。
     */
    private String modelsUrl;

    /**
     * 请求上游模型列表时附带的请求头。
     */
    private Map<String, String> headers;
}
