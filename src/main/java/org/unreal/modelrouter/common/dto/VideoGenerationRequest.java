package org.unreal.modelrouter.common.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频生成任务创建请求（POST /v1/videos/generations）。
 *
 * 字段定义参照火山方舟 Seedance 创建视频生成任务协议，端点命名与错误格式向 OpenAI 靠拢。
 * 已知字段类型化（Swagger 自动生成 schema）；未知字段通过 {@link JsonAnySetter} 兜底，
 * 序列化时经 {@link JsonAnyGetter} 原样平铺回顶层，保证向上游透传不丢字段。
 */
@Data
@Schema(description = "视频生成任务创建请求")
public class VideoGenerationRequest {

    @Schema(description = "模型 ID，如 doubao-seedance-2-5-251215", example = "doubao-seedance-2-5-251215", required = true)
    private String model;

    @Schema(description = "输入内容列表：文本 / 图片(image_url) / 视频(video_url) / 音频(audio_url) / 样片任务(draft_task)，含 role 标记首尾帧与参考素材", required = true)
    private JsonNode content;

    @Schema(description = "视频分辨率：480p / 720p / 1080p / 4k（不同模型支持范围不同）", example = "720p")
    private String resolution;

    @Schema(description = "视频宽高比：16:9 / 4:3 / 1:1 / 3:4 / 9:16 / 21:9 / adaptive", example = "16:9")
    private String ratio;

    @Schema(description = "视频时长（秒），与 frames 二选一；-1 表示模型智能选择", example = "5")
    private Integer duration;

    @Schema(description = "视频帧数（优先级高于 duration），取值 [29, 289] 内满足 25+4n 的整数", example = "121")
    private Integer frames;

    @Schema(description = "是否生成有声视频，默认 true（仅部分模型支持）", example = "true")
    @JsonProperty("generate_audio")
    private Boolean generateAudio;

    @Schema(description = "是否添加 AI 生成水印，默认 false", example = "false")
    private Boolean watermark;

    @Schema(description = "随机种子，-1 表示随机，取值 [-1, 2147483647]", example = "11")
    private Long seed;

    @Schema(description = "是否固定摄像头（平台在提示词中追加，效果不保证）", example = "false")
    @JsonProperty("camera_fixed")
    private Boolean cameraFixed;

    @Schema(description = "是否返回生成视频的尾帧图像，默认 false", example = "false")
    @JsonProperty("return_last_frame")
    private Boolean returnLastFrame;

    @Schema(description = "样片模式（Draft），默认 false；开启后以 480p 生成低成本预览视频", example = "false")
    private Boolean draft;

    @Schema(description = "服务等级：default 在线推理 / flex 离线推理", example = "default")
    @JsonProperty("service_tier")
    private String serviceTier;

    @Schema(description = "任务状态变化时的回调通知地址（上游直接回调客户端）")
    @JsonProperty("callback_url")
    private String callbackUrl;

    @Schema(description = "任务超时阈值（秒），从创建时间起算，默认 172800（48 小时），取值 [3600, 259200]", example = "172800")
    @JsonProperty("execution_expires_after")
    private Integer executionExpiresAfter;

    @Schema(description = "执行优先级，数值越大越靠前，取值 [0, 9]，默认 0", example = "0")
    private Integer priority;

    @Schema(description = "终端用户唯一标识（建议哈希后传入，最长 64 字符）")
    @JsonProperty("safety_identifier")
    private String safetyIdentifier;

    @Schema(description = "全模态参考任务类型引导：auto / reference / edit / extend（仅 Seedance 2.5）", example = "auto")
    @JsonProperty("omni_reference_task_type")
    private String omniReferenceTaskType;

    @Schema(description = "输出视频格式：mp4 / mov（mov 仅 Seedance 2.5 支持）", example = "mp4")
    @JsonProperty("output_format")
    private String outputFormat;

    @Schema(description = "工具配置列表，如 [{\"type\": \"web_search\"}]")
    private JsonNode tools;

    /** 未知字段兜底（透传保留，不参与 Swagger schema） */
    @JsonIgnore
    private Map<String, Object> extra = new LinkedHashMap<>();

    @JsonAnySetter
    public void putExtra(final String key, final Object value) {
        this.extra.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> anyExtra() {
        return this.extra;
    }
}
