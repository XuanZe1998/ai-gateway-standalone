package org.unreal.modelrouter.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.unreal.modelrouter.common.dto.ServiceInstanceDTO;

import java.util.List;

/**
 * 批量导入模型结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelImportResult {

    private Integer importedCount;
    private Integer skippedCount;
    private List<String> skippedModels;
    private List<ServiceInstanceDTO> instances;
}
