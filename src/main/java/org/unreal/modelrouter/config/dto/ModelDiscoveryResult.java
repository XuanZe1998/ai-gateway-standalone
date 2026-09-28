package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 上游模型发现结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDiscoveryResult {

    private String modelsUrl;
    private Integer count;
    private List<DiscoveredModelDTO> models;
}
