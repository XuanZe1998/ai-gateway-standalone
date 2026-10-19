-- Budget consumption is the normally priced amount before free-use exemptions.
-- It is neither an actual debit nor an upstream cost. Unknown historical values stay NULL.
ALTER TABLE ai_billing_record ADD COLUMN usage_value NUMERIC(26,8) CHECK (usage_value >= 0);
ALTER TABLE campus_usage_reservation ADD COLUMN reserved_value NUMERIC(26,8) CHECK (reserved_value >= 0);
ALTER TABLE campus_usage_reservation ADD COLUMN actual_value NUMERIC(26,8) CHECK (actual_value >= 0);
UPDATE ai_billing_record SET usage_value=total_cost
 WHERE total_cost>0 AND NOT is_admin_free_usage AND NOT COALESCE(is_free_quota,false);
UPDATE campus_usage_reservation r SET actual_value=b.usage_value FROM ai_billing_record b
 WHERE r.billing_record_id=b.id OR b.billing_request_id=r.request_id;
UPDATE campus_usage_reservation SET reserved_value=reserved_cost WHERE reserved_cost>0 AND NOT free_usage;
