package org.unreal.modelrouter.billing.rule;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * 计费上下文：请求处理时构建，传递给条件树引擎与规则匹配引擎。
 *
 * <p>条件树叶子节点按字段名取值（{@link #get(String)}）；未知字段返回 null，
 * 叶子比较时 null != targetValue → 条件不命中。
 *
 * <p>新增上下文字段：在字段列表 + {@link #get(String)} 加一个 case 即可，
 * 条件树引擎与计费核心逻辑无需改动。
 */
public class UsageContext {

    // ===== 初期实现字段 =====
    private final long tokenCount;
    private final long outputTokenCount;
    private final boolean stream;
    private final boolean hasToolCall;
    private final long contextLength;
    private final int hour;
    private final int weekday;
    private final boolean isWeekend;
    private final int month;
    private final String modelType;
    private final String vendor;
    private final boolean cacheHit;
    private final boolean thinkingEnabled;

    /**
     * 全字段构造。
     *
     * @param now 请求时间（用于派生 hour/weekday/isWeekend/month）
     */
    public UsageContext(long tokenCount, long outputTokenCount, boolean stream,
                        boolean hasToolCall, long contextLength, LocalDateTime now,
                        String modelType, String vendor,
                        boolean cacheHit, boolean thinkingEnabled) {
        this.tokenCount = tokenCount;
        this.outputTokenCount = outputTokenCount;
        this.stream = stream;
        this.hasToolCall = hasToolCall;
        this.contextLength = contextLength;
        DayOfWeek dow = now != null ? now.getDayOfWeek() : DayOfWeek.MONDAY;
        this.hour = now != null ? now.getHour() : 0;
        this.weekday = dow.getValue();
        this.isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        this.month = now != null ? now.getMonthValue() : 1;
        this.modelType = modelType;
        this.vendor = vendor;
        this.cacheHit = cacheHit;
        this.thinkingEnabled = thinkingEnabled;
    }

    /**
     * 通用字段取值：条件树引擎按字段名取值。
     * 未知字段返回 null，叶子比较时 null != targetValue → 不命中。
     */
    public Object get(String field) {
        return switch (field) {
            case "tokenCount" -> tokenCount;
            case "outputTokenCount" -> outputTokenCount;
            case "stream" -> stream;
            case "hasToolCall" -> hasToolCall;
            case "contextLength" -> contextLength;
            case "hour" -> hour;
            case "weekday" -> weekday;
            case "isWeekend" -> isWeekend;
            case "month" -> month;
            case "modelType" -> modelType;
            case "vendor" -> vendor;
            case "cacheHit" -> cacheHit;
            case "thinkingEnabled" -> thinkingEnabled;
            // 预留字段（dateRange/userType/accountTier/apiKeyType/hasFreeQuota/department/modelVersion）
            // 后续按需开放：在此加 case + 对应实例字段即可，引擎与计费核心零改动
            default -> null;
        };
    }

    // ===== 标准 getter（供规则引擎/计费侧使用）=====
    public long getTokenCount() { return tokenCount; }
    public long getOutputTokenCount() { return outputTokenCount; }
    public boolean isStream() { return stream; }
    public boolean isHasToolCall() { return hasToolCall; }
    public long getContextLength() { return contextLength; }
    public int getHour() { return hour; }
    public int getWeekday() { return weekday; }
    public boolean isWeekend() { return isWeekend; }
    public int getMonth() { return month; }
    public String getModelType() { return modelType; }
    public String getVendor() { return vendor; }
    public boolean isCacheHit() { return cacheHit; }
    public boolean isThinkingEnabled() { return thinkingEnabled; }
}