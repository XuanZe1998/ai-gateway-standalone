package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 从上游发现的模型信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveredModelDTO {

    private String id;
    private String object;
    private String ownedBy;
}
