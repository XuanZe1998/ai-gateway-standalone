package org.unreal.modelrouter.billing.usage;

import com.fasterxml.jackson.databind.JsonNode;

/** Non-token billing metadata, taken from the actual request/response. */
public final class RequestBillingMetadata {
    private RequestBillingMetadata() { }

    /** FilePart is a live upload stream, not JSON. Never serialize it while settling a call. */
    public static JsonNode requestMetadata(com.fasterxml.jackson.databind.ObjectMapper mapper, Object request) {
        if (request instanceof org.unreal.modelrouter.common.dto.ImageEditDTO.Request edit)
            return mapper.createObjectNode().put("model", edit.model()).put("n", edit.n());
        if (request instanceof org.unreal.modelrouter.common.dto.SttDTO.Request stt)
            return mapper.createObjectNode().put("model", stt.model()).put("response_format", stt.responseFormat());
        return mapper.valueToTree(request);
    }

    /** 只检查结构化音频项，不扫描正文或读取音频内容。 */
    public static boolean hasAudioInput(JsonNode request) {
        if (request == null || (!request.isObject() && !request.isArray())) return false;
        String type = request.path("type").asText("");
        if ("input_audio".equals(type) || "audio_url".equals(type)) return true;
        if (request.isObject() && (request.has("input_audio") || request.has("audio_url"))) return true;
        for (JsonNode child : request) if (hasAudioInput(child)) return true;
        return false;
    }

    public static boolean fastMode(JsonNode request, JsonNode response) {
        String tier = response == null ? "" : response.path("service_tier").asText("");
        if (tier.isBlank() && request != null) {
            tier = request.path("service_tier").asText("");
            if (tier.isBlank()) tier = request.path("options").path("service_tier").asText("");
        }
        return "priority".equalsIgnoreCase(tier) || "fast".equalsIgnoreCase(tier);
    }

    public static long imageCount(JsonNode response) {
        if (response == null) return 0;
        JsonNode images = response.path("data");
        if (!images.isArray()) images = images.path("data");
        if (!images.isArray()) return 0;
        long count = 0;
        for (JsonNode item : images) {
            if (item.path("error").isObject()) continue;
            if (!item.path("url").asText("").isBlank()
                    || !item.path("b64_json").asText("").isBlank()) count++;
        }
        return count;
    }

    public static java.math.BigDecimal inputVideoSeconds(JsonNode response) {
        if (response == null) return null;
        for (JsonNode node : new JsonNode[]{response.path("usage"), response}) {
            for (String key : new String[]{"input_video_duration", "input_video_seconds", "input_duration"}) {
                JsonNode value = node.path(key);
                if (value.isNumber() && value.decimalValue().signum() >= 0) return value.decimalValue();
            }
        }
        return null;
    }
}
