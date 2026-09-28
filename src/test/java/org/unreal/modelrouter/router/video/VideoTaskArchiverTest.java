package org.unreal.modelrouter.router.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.ModelPricingService;
import org.unreal.modelrouter.persistence.jpa.entity.VideoTaskEntity;
import org.unreal.modelrouter.persistence.jpa.repository.VideoTaskRepository;
import org.unreal.modelrouter.router.model.ModelRouterProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VideoTaskArchiver} 单元测试：请求快照 base64 脱敏、留档失败不影响响应。
 */
@ExtendWith(MockitoExtension.class)
class VideoTaskArchiverTest {

    @Mock
    private VideoTaskRepository repository;
    @Mock
    private ModelPricingService pricingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private VideoTaskArchiver archiver() {
        return new VideoTaskArchiver(repository, objectMapper, pricingService);
    }

    private ObjectNode requestWithBase64(final String base64Payload) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("model", "doubao-seedance-2-5-251215");
        node.put("resolution", "720p");
        node.put("ratio", "16:9");
        node.put("duration", 5);
        ArrayNode content = node.putArray("content");
        ObjectNode textItem = content.addObject();
        textItem.put("role", "user");
        textItem.put("type", "text");
        textItem.put("text", "一只猫在草地上奔跑");
        ObjectNode imageItem = content.addObject();
        imageItem.put("type", "image_url");
        imageItem.put("image_url", "data:image/png;base64," + base64Payload);
        return node;
    }

    @Test
    void archiveSubmitted_sanitizesBase64AndExtractsKeyParams() {
        String payload = "A".repeat(200_000);
        VideoTaskArchiver archiver = archiver();
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("ark-test");
        instance.setVendor("volcengine");
        instance.setBaseUrl("https://ark.cn-beijing.volces.com");

        archiver.archiveSubmitted("vidtask_abc", "ark-task-123", null,
                "doubao-seedance-2-5-251215", instance, requestWithBase64(payload),
                "{\"id\":\"ark-task-123\"}", UserIdentity.SYSTEM, "10.0.0.1", "trace-1");

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        VideoTaskEntity entity = captor.getValue();

        assertThat(entity.getTaskNo()).isEqualTo("vidtask_abc");
        assertThat(entity.getUpstreamTaskId()).isEqualTo("ark-task-123");
        // 上游未返回 status 时用 submitted
        assertThat(entity.getStatus()).isEqualTo("submitted");
        // 关键参数提取
        assertThat(entity.getResolution()).isEqualTo("720p");
        assertThat(entity.getRatio()).isEqualTo("16:9");
        assertThat(entity.getDuration()).isEqualTo(5);
        // 无 video_url 输入项 → 无视频输入
        assertThat(entity.getHasVideoInput()).isFalse();
        // 快照脱敏：base64 载荷被截断为标记，原始大载荷不入库
        String snapshot = entity.getRequestSnapshot();
        assertThat(snapshot).contains("<base64 truncated, original length");
        assertThat(snapshot).doesNotContain("A".repeat(1000));
        // URL/文本内容仍保留，可追溯素材来源与提示词
        assertThat(snapshot).contains("一只猫在草地上奔跑");
        assertThat(entity.getFirstResponseSnapshot()).contains("ark-task-123");
    }

    @Test
    void archiveSubmitted_publicUrlKeptAsIs() {
        VideoTaskArchiver archiver = archiver();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("model", "m");
        ArrayNode content = node.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "image_url");
        item.put("image_url", "https://cdn.example.com/pic.jpg");

        archiver.archiveSubmitted("vidtask_url", "up-1", "queued", "m", null,
                node, "{}", UserIdentity.SYSTEM, null, null);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        assertThat(captor.getValue().getRequestSnapshot()).contains("https://cdn.example.com/pic.jpg");
    }

    @Test
    void archiveSubmitted_withVideoUrlInput_marksHasVideoInputTrue() {
        VideoTaskArchiver archiver = archiver();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("model", "m");
        ArrayNode content = node.putArray("content");
        ObjectNode textItem = content.addObject();
        textItem.put("type", "text");
        textItem.put("text", "基于参考视频生成新视频");
        ObjectNode videoItem = content.addObject();
        videoItem.put("type", "video_url");
        videoItem.put("video_url", "https://cdn.example.com/ref.mp4");

        archiver.archiveSubmitted("vidtask_video_in", "up-2", "queued", "m", null,
                node, "{}", UserIdentity.SYSTEM, null, null);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        assertThat(captor.getValue().getHasVideoInput()).isTrue();
    }

    @Test
    void archiveSubmitted_withoutContent_hasVideoInputNull() {
        // content 缺失（协议异常/弱校验传参）：无法判定，置 null（按条件定价时计费侧拒计费兜底）
        VideoTaskArchiver archiver = archiver();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("model", "m");

        archiver.archiveSubmitted("vidtask_no_content", "up-3", "queued", "m", null,
                node, "{}", UserIdentity.SYSTEM, null, null);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        assertThat(captor.getValue().getHasVideoInput()).isNull();
    }

    @Test
    void archiveSubmitFailed_saveFailureDoesNotPropagate() {
        // 落库异常仅告警，不向调用方传播（留档失败不影响客户端响应）
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        VideoTaskArchiver archiver = archiver();

        assertThatCode(() -> archiver.archiveSubmitFailed("vidtask_fail", "m", null,
                objectMapper.createObjectNode(), "504", "上游服务响应超时",
                UserIdentity.SYSTEM, null, null)).doesNotThrowAnyException();

        // 确认异步落库确实被尝试过（save 抛异常仍计为一次调用）
        verify(repository, timeout(3000)).save(any());
    }

    @Test
    void archiveSubmitted_platformUserTrue_persistedToEntity() {
        // 平台企业用户创建任务：platformUser 标记必须落档（轮询重建身份依赖该列恢复企业折扣）
        VideoTaskArchiver archiver = archiver();
        UserIdentity platformIdentity = new UserIdentity("u1", "u1-acct", "key-1", "key-name",
                1L, "ent", "C001", true, 1, 7L, null);

        archiver.archiveSubmitted("vidtask_platform", "up-9", "queued", "m", null,
                objectMapper.createObjectNode().put("model", "m"), "{}", platformIdentity, null, null);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        assertThat(captor.getValue().getPlatformUser()).isTrue();
    }

    @Test
    void archiveSubmitted_localKeyUser_platformUserFalsePersisted() {
        // 本地 API Key / SYSTEM 用户：platformUser=false 落档（轮询重建仍按非平台用户，不享企业折扣）
        VideoTaskArchiver archiver = archiver();

        archiver.archiveSubmitted("vidtask_local", "up-10", "queued", "m", null,
                objectMapper.createObjectNode().put("model", "m"), "{}", UserIdentity.SYSTEM, null, null);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        assertThat(captor.getValue().getPlatformUser()).isFalse();
    }

    @Test
    void archiveSubmitted_snapshotsBillingRulesFromPricingCache() {
        // 创建时锁定提交时刻的计费规则（V7：平台侧后续改价/删规则不影响已提交任务的账单生成）
        when(pricingService.snapshotVideoPricing("doubao-seedance-2-5-251215", "19"))
                .thenReturn("{\"priceMode\":2,\"billingUnit\":\"second\",\"modelId\":37," +
                        "\"rules\":[{\"outputResolution\":\"480P\",\"hasVideoInput\":false,\"price\":46.0}]}");
        VideoTaskArchiver archiver = archiver();
        ModelRouterProperties.ModelInstance instance = new ModelRouterProperties.ModelInstance();
        instance.setName("ark-test");
        instance.setVendor("volcengine");
        instance.setChannelId("19");
        instance.setBaseUrl("https://ark.cn-beijing.volces.com");

        archiver.archiveSubmitted("vidtask_snap", "up-11", "queued", "doubao-seedance-2-5-251215", instance,
                objectMapper.createObjectNode().put("model", "doubao-seedance-2-5-251215"),
                "{}", UserIdentity.SYSTEM, null, null);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        String snapshot = captor.getValue().getBillingRuleSnapshot();
        assertThat(snapshot).contains("\"priceMode\":2").contains("\"480P\"").contains("\"modelId\":37");
        // 快照按模型名+渠道取定价
        verify(pricingService).snapshotVideoPricing("doubao-seedance-2-5-251215", "19");
    }

    @Test
    void archiveSubmitted_pricingMissing_snapshotNull() {
        // 创建时定价缓存未命中：快照留空，结算回退实时定价（兼容历史数据）
        when(pricingService.snapshotVideoPricing(any(), any())).thenReturn(null);
        VideoTaskArchiver archiver = archiver();

        archiver.archiveSubmitted("vidtask_no_pricing", "up-12", "queued", "m", null,
                objectMapper.createObjectNode().put("model", "m"), "{}", UserIdentity.SYSTEM, null, null);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(repository, timeout(3000)).save(captor.capture());
        assertThat(captor.getValue().getBillingRuleSnapshot()).isNull();
    }
}
