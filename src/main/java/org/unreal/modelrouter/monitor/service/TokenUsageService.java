package org.unreal.modelrouter.monitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.unreal.modelrouter.monitor.dto.TokenUsageRecordDTO;
import org.unreal.modelrouter.monitor.dto.TokenUsageStatisticsDTO;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import org.unreal.modelrouter.persistence.jpa.entity.TokenUsageEntity;
import org.unreal.modelrouter.persistence.jpa.repository.BillingRecordRepository;
import org.unreal.modelrouter.persistence.jpa.repository.TokenUsageRepository;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Token 使用量统计服务。
 *
 * <p>查询统一以 {@code ai_billing_record} 为权威数据源。旧的 {@code token_usage}
 * 表只保留手工写入和清理接口的兼容能力，不再参与仪表盘统计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private static final int MAX_RECENT_LIMIT = 100;

    private final TokenUsageRepository tokenUsageRepository;
    private final BillingRecordRepository billingRecordRepository;

    /** 兼容旧的手工记录接口。网关自动计费链路不调用此方法。 */
    @Transactional
    public void recordTokenUsage(final TokenUsageRecordDTO record) {
        try {
            TokenUsageEntity entity = TokenUsageEntity.builder()
                    .traceId(record.getTraceId())
                    .serviceType(record.getServiceType())
                    .modelName(record.getModelName())
                    .provider(record.getProvider())
                    .instanceName(record.getInstanceName())
                    .instanceUrl(record.getInstanceUrl())
                    .promptTokens(value(record.getPromptTokens()))
                    .completionTokens(value(record.getCompletionTokens()))
                    .totalTokens(value(record.getTotalTokens()))
                    .apiKeyId(record.getApiKeyId())
                    .userId(record.getUserId())
                    .clientIp(record.getClientIp())
                    .isSuccess(record.getIsSuccess())
                    .errorCode(record.getErrorCode())
                    .errorMessage(record.getErrorMessage())
                    .responseTimeMs(record.getResponseTimeMs())
                    .occurredAt(record.getOccurredAt() != null ? record.getOccurredAt() : LocalDateTime.now())
                    .metadata(record.getMetadata())
                    .build();
            tokenUsageRepository.save(entity);
        } catch (Exception e) {
            log.error("Failed to record legacy token usage", e);
        }
    }

    /** 兼容旧的手工批量记录接口。 */
    @Transactional
    public void recordTokenUsageBatch(final List<TokenUsageRecordDTO> records) {
        records.forEach(this::recordTokenUsage);
    }

    @Transactional(readOnly = true)
    public TokenUsageStatisticsDTO getTokenUsageStatistics(
            final LocalDateTime startTime, final LocalDateTime endTime) {
        LocalDateTime effectiveStart = startTime != null ? startTime : LocalDateTime.now().minusDays(7);
        LocalDateTime effectiveEnd = endTime != null ? endTime : LocalDateTime.now();
        List<BillingRecordEntity> records = findRecords(effectiveStart, effectiveEnd);

        long totalRequests = records.size();
        long successfulRequests = records.stream().filter(r -> Boolean.TRUE.equals(r.getIsSuccess())).count();
        long failedRequests = records.stream().filter(r -> Boolean.FALSE.equals(r.getIsSuccess())).count();
        long totalTokens = records.stream().mapToLong(r -> value(r.getTotalTokens())).sum();
        long promptTokens = records.stream().mapToLong(r -> value(r.getPromptTokens())).sum();
        long completionTokens = records.stream().mapToLong(r -> value(r.getCompletionTokens())).sum();
        double averageResponseTime = records.stream()
                .map(BillingRecordEntity::getResponseTimeMs)
                .filter(v -> v != null)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);

        TokenUsageStatisticsDTO dto = TokenUsageStatisticsDTO.builder()
                .startTime(effectiveStart)
                .endTime(effectiveEnd)
                .totalRequests(totalRequests)
                .successfulRequests(successfulRequests)
                .failedRequests(failedRequests)
                .totalTokens(totalTokens)
                .totalPromptTokens(promptTokens)
                .totalCompletionTokens(completionTokens)
                .avgResponseTimeMs(averageResponseTime)
                .successRate(totalRequests > 0 ? successfulRequests * 100.0 / totalRequests : 0.0)
                .build();

        dto.setByModel(buildModelStats(records));
        dto.setByServiceType(buildServiceTypeStats(records));
        dto.setByProvider(buildProviderStats(records));
        dto.setByDay(buildDailyStats(records));
        dto.setByWeek(buildWeeklyStats(records));
        dto.setByMonth(buildMonthlyStats(records));
        dto.setByHour(buildHourlyStats(records));
        dto.setByApiKey(buildApiKeyStats(records));
        dto.setByUser(buildUserStats(records));
        return dto;
    }

    @Transactional(readOnly = true)
    public List<TokenUsageRecordDTO> getRecentUsage(final int limit) {
        return billingRecordRepository.findByIsDeletedFalseOrderByStartedAtDesc(page(limit)).stream()
                .map(this::toUsageRecord)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TokenUsageRecordDTO> getRecentUsageByModel(final String modelName, final int limit) {
        return billingRecordRepository
                .findByModelNameAndIsDeletedFalseOrderByStartedAtDesc(modelName, page(limit)).stream()
                .map(this::toUsageRecord)
                .toList();
    }

    /** 只清理废弃的 token_usage 兼容表；账单属于财务记录，不在这里删除。 */
    @Transactional
    public int deleteOldUsageRecords(final LocalDateTime cutoffTime) {
        log.info("删除截止时间 {} 之前的旧 Token 使用兼容记录", cutoffTime);
        return tokenUsageRepository.deleteByOccurredAtBefore(cutoffTime);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTopModels(
            final LocalDateTime startTime, final LocalDateTime endTime, final int limit) {
        List<TokenUsageStatisticsDTO.ModelTokenStats> stats = buildModelStats(findRecords(startTime, endTime));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(normalizeLimit(limit), stats.size()); i++) {
            TokenUsageStatisticsDTO.ModelTokenStats item = stats.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("modelName", item.getModelName());
            row.put("totalTokens", item.getTotalTokens());
            row.put("promptTokens", item.getPromptTokens());
            row.put("completionTokens", item.getCompletionTokens());
            row.put("requestCount", item.getRequestCount());
            result.add(row);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTopServiceTypes(
            final LocalDateTime startTime, final LocalDateTime endTime, final int limit) {
        List<TokenUsageStatisticsDTO.ServiceTypeStats> stats = buildServiceTypeStats(findRecords(startTime, endTime));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(normalizeLimit(limit), stats.size()); i++) {
            TokenUsageStatisticsDTO.ServiceTypeStats item = stats.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("serviceType", item.getServiceType());
            row.put("totalTokens", item.getTotalTokens());
            row.put("promptTokens", item.getPromptTokens());
            row.put("completionTokens", item.getCompletionTokens());
            row.put("requestCount", item.getRequestCount());
            result.add(row);
        }
        return result;
    }

    private List<BillingRecordEntity> findRecords(
            final LocalDateTime startTime, final LocalDateTime endTime) {
        return billingRecordRepository.findByStartedAtBetweenAndIsDeletedFalseOrderByStartedAtDesc(
                startTime, endTime);
    }

    private List<TokenUsageStatisticsDTO.ModelTokenStats> buildModelStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, BillingRecordEntity::getModelName).entrySet().stream()
                .sorted(byTotalTokensDescending())
                .map(e -> new TokenUsageStatisticsDTO.ModelTokenStats(
                        e.getKey(), e.getValue().totalTokens, e.getValue().promptTokens,
                        e.getValue().completionTokens, e.getValue().requestCount,
                        e.getValue().requestCount > 0
                                ? (double) e.getValue().totalTokens / e.getValue().requestCount : 0.0))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.ServiceTypeStats> buildServiceTypeStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, BillingRecordEntity::getServiceType).entrySet().stream()
                .sorted(byTotalTokensDescending())
                .map(e -> new TokenUsageStatisticsDTO.ServiceTypeStats(
                        e.getKey(), e.getValue().totalTokens, e.getValue().promptTokens,
                        e.getValue().completionTokens, e.getValue().requestCount))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.ProviderStats> buildProviderStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, this::providerName).entrySet().stream()
                .sorted(byTotalTokensDescending())
                .map(e -> new TokenUsageStatisticsDTO.ProviderStats(
                        e.getKey(), e.getValue().totalTokens, e.getValue().requestCount))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.DailyStats> buildDailyStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, r -> r.getStartedAt().toLocalDate().toString()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new TokenUsageStatisticsDTO.DailyStats(
                        e.getKey(), e.getValue().totalTokens, e.getValue().promptTokens,
                        e.getValue().completionTokens, e.getValue().requestCount))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.WeeklyStats> buildWeeklyStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, r -> new WeekKey(
                r.getStartedAt().get(IsoFields.WEEK_BASED_YEAR),
                r.getStartedAt().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new TokenUsageStatisticsDTO.WeeklyStats(
                        e.getKey().year, e.getKey().week, e.getValue().totalTokens,
                        e.getValue().promptTokens, e.getValue().completionTokens,
                        e.getValue().requestCount,
                        e.getKey().year + "-W" + String.format("%02d", e.getKey().week)))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.MonthlyStats> buildMonthlyStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, r -> YearMonth.from(r.getStartedAt())).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new TokenUsageStatisticsDTO.MonthlyStats(
                        e.getKey().getYear(), e.getKey().getMonthValue(), e.getValue().totalTokens,
                        e.getValue().promptTokens, e.getValue().completionTokens,
                        e.getValue().requestCount, e.getKey().toString()))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.HourlyStats> buildHourlyStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, r -> r.getStartedAt().getHour()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new TokenUsageStatisticsDTO.HourlyStats(
                        e.getKey(), e.getValue().totalTokens, e.getValue().requestCount,
                        String.format("%02d:00", e.getKey())))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.ApiKeyStats> buildApiKeyStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, BillingRecordEntity::getApiKeyId).entrySet().stream()
                .sorted(byTotalTokensDescending())
                .map(e -> new TokenUsageStatisticsDTO.ApiKeyStats(
                        e.getKey(), e.getValue().totalTokens, e.getValue().requestCount))
                .toList();
    }

    private List<TokenUsageStatisticsDTO.UserStats> buildUserStats(
            final List<BillingRecordEntity> records) {
        return aggregate(records, BillingRecordEntity::getUserId).entrySet().stream()
                .sorted(byTotalTokensDescending())
                .map(e -> new TokenUsageStatisticsDTO.UserStats(
                        e.getKey(), e.getValue().totalTokens, e.getValue().requestCount))
                .toList();
    }

    private <K> Map<K, Aggregate> aggregate(
            final List<BillingRecordEntity> records,
            final Function<BillingRecordEntity, K> classifier) {
        Map<K, Aggregate> result = new LinkedHashMap<>();
        for (BillingRecordEntity record : records) {
            if (record.getStartedAt() == null) {
                continue;
            }
            K key = classifier.apply(record);
            if (key != null) {
                result.computeIfAbsent(key, ignored -> new Aggregate()).add(record);
            }
        }
        return result;
    }

    private static <K> Comparator<Map.Entry<K, Aggregate>> byTotalTokensDescending() {
        return Comparator.<Map.Entry<K, Aggregate>>comparingLong(e -> e.getValue().totalTokens).reversed();
    }

    private TokenUsageRecordDTO toUsageRecord(final BillingRecordEntity record) {
        return TokenUsageRecordDTO.builder()
                .traceId(record.getTraceId())
                .serviceType(record.getServiceType())
                .modelName(record.getModelName())
                .provider(providerName(record))
                .instanceName(record.getChannelName())
                .promptTokens(value(record.getPromptTokens()))
                .completionTokens(value(record.getCompletionTokens()))
                .totalTokens(value(record.getTotalTokens()))
                .apiKeyId(record.getApiKeyId())
                .userId(record.getUserId())
                .clientIp(record.getClientIp())
                .isSuccess(record.getIsSuccess())
                .errorCode(record.getErrorCode())
                .errorMessage(record.getErrorMessage())
                .responseTimeMs(record.getResponseTimeMs())
                .occurredAt(record.getStartedAt())
                .build();
    }

    private String providerName(final BillingRecordEntity record) {
        if (record.getVendor() != null && !record.getVendor().isBlank()) {
            return record.getVendor();
        }
        return record.getProvider();
    }

    private PageRequest page(final int limit) {
        return PageRequest.of(0, normalizeLimit(limit));
    }

    private int normalizeLimit(final int limit) {
        return Math.max(1, Math.min(limit, MAX_RECENT_LIMIT));
    }

    private static long value(final Long number) {
        return number != null ? number : 0L;
    }

    private record WeekKey(int year, int week) implements Comparable<WeekKey> {
        @Override
        public int compareTo(final WeekKey other) {
            int yearComparison = Integer.compare(year, other.year);
            return yearComparison != 0 ? yearComparison : Integer.compare(week, other.week);
        }
    }

    private static final class Aggregate {
        private long totalTokens;
        private long promptTokens;
        private long completionTokens;
        private long requestCount;

        private void add(final BillingRecordEntity record) {
            totalTokens += value(record.getTotalTokens());
            promptTokens += value(record.getPromptTokens());
            completionTokens += value(record.getCompletionTokens());
            requestCount++;
        }
    }
}
