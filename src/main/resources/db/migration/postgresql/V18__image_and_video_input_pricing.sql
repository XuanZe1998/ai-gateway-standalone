-- Images use the existing pricing rule table; video input uses CNY/second.
ALTER TABLE IF EXISTS ai_model_video_price ADD COLUMN IF NOT EXISTS input_price NUMERIC(20,10);
INSERT INTO ai_billing_dimension(group_key,dimension_key,display_name,unit,value_type,sort_order,enabled,created_at,updated_at)
VALUES ('image','imagePrice','图片输出','元 / 张','price',20,true,now(),now())
ON CONFLICT (group_key,dimension_key) DO NOTHING;
