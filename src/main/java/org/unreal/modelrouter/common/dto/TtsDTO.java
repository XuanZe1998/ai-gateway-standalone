// 文件说明：TtsDTO：负责通用基础能力中的数据传输。
package org.unreal.modelrouter.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TtsDTO {

    public record Request(
            String model,
            String input,
            String voice,
            @JsonProperty("response_format") String responseFormat,
            Double speed
    ) {
    }
}