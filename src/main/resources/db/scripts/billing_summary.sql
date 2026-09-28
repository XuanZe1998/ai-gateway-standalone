-- ========================================-- ai_billing_record 计费数据汇总（快速概览）-- ========================================

-- 1. 总体统计
SELECT
    COUNT(*) AS total_records,
    COUNT(DISTINCT model_name) AS model_count,
    COUNT(DISTINCT user_account) AS user_count,
    ROUND(SUM(total_cost), 6) AS total_revenue,
    ROUND(AVG(total_cost), 6) AS avg_cost_per_request,
    ROUND(AVG(response_time_ms), 2) AS avg_response_time_ms
FROM ai_billing_record
WHERE is_deleted = false;

-- 2. 按模型维度汇总（收入 Top 10）
SELECT
    model_name,
    channel_id,
    COUNT(*) AS request_count,
    SUM(prompt_tokens) AS total_prompt_tokens,
    SUM(completion_tokens) AS total_completion_tokens,
    SUM(total_tokens) AS total_tokens,
    ROUND(AVG(input_unit_price), 10) AS input_price,
    ROUND(AVG(output_unit_price), 10) AS output_price,
    ROUND(AVG(discount_rate), 2) AS avg_discount_rate,
    ROUND(SUM(original_cost), 6) AS total_original_cost,
    ROUND(SUM(discount_amount), 6) AS total_discount,
    ROUND(SUM(total_cost), 6) AS total_revenue
FROM ai_billing_record
WHERE is_deleted = false
  AND is_success = true
GROUP BY model_name, channel_id
ORDER BY total_revenue DESC
LIMIT 10;

-- 3. 检查是否有异常记录（价格为 0 或折扣异常）
SELECT
    id,
    model_name,
    prompt_tokens,
    completion_tokens,
    input_unit_price,
    output_unit_price,
    discount_rate,
    original_cost,
    total_cost,
    CASE
        WHEN input_unit_price = 0 AND output_unit_price = 0 THEN 'zero_price'
        WHEN discount_rate < 0 OR discount_rate > 1 THEN 'invalid_discount'
        WHEN original_cost = 0 AND (prompt_tokens > 0 OR completion_tokens > 0) THEN 'zero_cost_with_tokens'
        ELSE 'ok'
    END AS anomaly_type
FROM ai_billing_record
WHERE is_deleted = false
  AND (
    (input_unit_price = 0 AND output_unit_price = 0)
    OR discount_rate < 0 OR discount_rate > 1
    OR (original_cost = 0 AND (prompt_tokens > 0 OR completion_tokens > 0))
  )
ORDER BY id DESC;
