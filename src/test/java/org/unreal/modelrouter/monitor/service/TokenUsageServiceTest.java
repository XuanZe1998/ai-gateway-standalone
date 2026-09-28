// 文件说明：测试 TokenUsageServiceTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.monitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.dto.TokenUsageStatisticsDTO;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenUsageServiceTest {

    @Mock
    private TokenUsageRepository tokenUsageRepository;

    @Mock
    private BillingRecordRepository billingRecordRepository;

    private TokenUsageService service;

    @BeforeEach
    void setUp() {
        service = new TokenUsageService(tokenUsageRepository, billingRecordRepository);
    }

    @Test
    void shouldBuildStatisticsFromBillingRecords() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 14, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 16, 0, 0);
        when(billingRecordRepository
                .findByStartedAtBetweenAndIsDeletedFalseOrderByStartedAtDesc(start, end))
                .thenReturn(List.of(
                        record("u1", "model-a", 10, 2, true,
                                LocalDateTime.of(2026, 9, 15, 10, 0)),
                        record("u2", "model-a", 20, 3, true,
                                LocalDateTime.of(2026, 9, 15, 11, 0)),
                        record("u1", "model-b", 5, 0, false,
                                LocalDateTime.of(2026, 9, 14, 9, 0))));

        TokenUsageStatisticsDTO result = service.getTokenUsageStatistics(start, end);

        assertThat(result.getTotalRequests()).isEqualTo(3);
        assertThat(result.getSuccessfulRequests()).isEqualTo(2);
        assertThat(result.getFailedRequests()).isEqualTo(1);
        assertThat(result.getTotalPromptTokens()).isEqualTo(35);
        assertThat(result.getTotalCompletionTokens()).isEqualTo(5);
        assertThat(result.getTotalTokens()).isEqualTo(40);
        assertThat(result.getByModel()).extracting(TokenUsageStatisticsDTO.ModelTokenStats::getModelName)
                .containsExactly("model-a", "model-b");
        assertThat(result.getByModel().get(0).getTotalTokens()).isEqualTo(35);
        assertThat(result.getByUser()).hasSize(2);
        assertThat(result.getByDay()).hasSize(2);
    }

    @Test
    void shouldMapRecentBillingRecordsToExistingFrontendContract() {
        BillingRecordEntity billing = record("user-1", "qwen-plus", 12, 1, true,
                LocalDateTime.of(2026, 9, 15, 12, 30));
        billing.setApiKeyId("key-1");
        billing.setChannelName("channel-a");
        billing.setVendor("qwen");
        when(billingRecordRepository.findByIsDeletedFalseOrderByStartedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(billing));

        List<TokenUsageRecordDTO> result = service.getRecentUsage(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo("user-1");
        assertThat(result.get(0).getApiKeyId()).isEqualTo("key-1");
        assertThat(result.get(0).getModelName()).isEqualTo("qwen-plus");
        assertThat(result.get(0).getProvider()).isEqualTo("qwen");
        assertThat(result.get(0).getInstanceName()).isEqualTo("channel-a");
        assertThat(result.get(0).getTotalTokens()).isEqualTo(13);
        assertThat(result.get(0).getOccurredAt()).isEqualTo(billing.getStartedAt());
    }

    private BillingRecordEntity record(
            final String userId,
            final String modelName,
            final long promptTokens,
            final long completionTokens,
            final boolean success,
            final LocalDateTime startedAt) {
        return BillingRecordEntity.builder()
                .userId(userId)
                .serviceType("chat")
                .modelName(modelName)
                .provider("openai")
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .isSuccess(success)
                .responseTimeMs(100L)
                .startedAt(startedAt)
                .isDeleted(false)
                .build();
    }
}
