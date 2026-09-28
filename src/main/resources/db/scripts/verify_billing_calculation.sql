-- ========================================-- ai_billing_record 计费计算校验脚本-- 用法: 在 PostgreSQL 中执行，找出计算异常的数据-- ========================================

WITH calculated AS (
    SELECT
        id,
        model_name,
        prompt_tokens,
        completion_tokens,
        total_tokens,
        input_unit_price,
        output_unit_price,
        discount_rate,
        original_cost,
        discount_amount,
        total_cost,
        -- 校验1: total_tokens 应该等于 prompt + completion
        CASE WHEN total_tokens != COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0)
             THEN 'token_sum_mismatch'
             ELSE 'ok' END AS token_check,
        -- 校验2: 重新计算 original_cost
        ROUND(
            COALESCE(prompt_tokens, 0) * COALESCE(input_unit_price, 0) +
            COALESCE(completion_tokens, 0) * COALESCE(output_unit_price, 0),
            6
        ) AS expected_original_cost,
        -- 校验3: 重新计算 discount_amount
        ROUND(
            ROUND(
                COALESCE(prompt_tokens, 0) * COALESCE(input_unit_price, 0) +
                COALESCE(completion_tokens, 0) * COALESCE(output_unit_price, 0),
                6
            ) * (1 - COALESCE(discount_rate, 1)),
            6
        ) AS expected_discount_amount,
        -- 校验4: 重新计算 total_cost
        ROUND(
            ROUND(
                COALESCE(prompt_tokens, 0) * COALESCE(input_unit_price, 0) +
                COALESCE(completion_tokens, 0) * COALESCE(output_unit_price, 0),
                6
            ) * COALESCE(discount_rate, 1),
            6
        ) AS expected_total_cost
    FROM ai_billing_record
    WHERE is_success = true
      AND is_deleted = false
)
SELECT
    id,
    model_name,
    prompt_tokens,
    completion_tokens,
    total_tokens,
    input_unit_price,
    output_unit_price,
    discount_rate,
    original_cost AS actual_original,
    expected_original_cost,
    discount_amount AS actual_discount,
    expected_discount_amount,
    total_cost AS actual_total,
    expected_total_cost,
    token_check,
    CASE
        WHEN original_cost != expected_original_cost THEN 'original_cost_error'
        WHEN discount_amount != expected_discount_amount THEN 'discount_amount_error'
        WHEN total_cost != expected_total_cost THEN 'total_cost_error'
        WHEN token_check != 'ok' THEN 'token_error'
        ELSE 'ok'
    END AS verify_result
FROM calculated
WHERE (
    original_cost != expected_original_cost
    OR discount_amount != expected_discount_amount
    OR total_cost != expected_total_cost
    OR token_check != 'ok'
)
ORDER BY id DESC;
