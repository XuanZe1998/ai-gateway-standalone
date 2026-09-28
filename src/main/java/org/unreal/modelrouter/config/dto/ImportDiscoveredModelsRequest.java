package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 批量导入发现模型的请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportDiscoveredModelsRequest {

    private List<String> modelIds;
    private String baseUrl;
    private String path;
    private Integer weight;
    private String status;
    private String adapter;
    private Map<String, String> headers;
}
