package org.unreal.modelrouter.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** PostgreSQL locks serialize budget reservations across replicas. No in-memory spending counters. */
@Service
public class DepartmentBudgetService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ModelPricingService pricing;
    private final TransactionTemplate tx;
    private DiscountCalculationService discounts;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setBudgetDiscounts(DiscountCalculationService service){this.discounts=service;}
    public DepartmentBudgetService(JdbcTemplate jdbc, ObjectMapper json, ModelPricingService pricing,
                                   PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.json=json; this.pricing=pricing; this.tx=new TransactionTemplate(manager);
    }

    public UserIdentity admit(UserIdentity identity, String service, String model, String channel, Object request) {
        if (identity == null || !identity.platformUser() || identity.usageSnapshot()!=null) return identity;
        return tx.execute(status -> {
            var members=jdbc.queryForList("SELECT * FROM campus_current_member WHERE user_id=?",identity.userId());
            if (members.isEmpty()) return identity;
            var member=members.get(0);
            String department=(String)member.get("department_id");
            lock(department);
            // Recheck after the lock: membership may have changed while we waited.
            member=jdbc.queryForMap("SELECT * FROM campus_current_member WHERE owner_id=?",member.get("owner_id"));
            if (!department.equals(member.get("department_id"))) throw conflict("部门发生变化，请重试调用");
            var policies=jdbc.queryForList("SELECT COALESCE(p.free_usage,d.free_usage,false) AS free, "
                    + "COALESCE(p.funding_source,d.funding_source,'PERSONAL') AS funding, "
                    + "COALESCE(p.monthly_cost,d.member_cost_limit) AS cost_limit, COALESCE(p.monthly_tokens,d.member_token_limit) AS token_limit, "
                    + "d.monthly_tokens AS department_tokens,COALESCE(d.version,0) AS version,COALESCE(p.updated_at,d.updated_at) AS policy_updated_at, "
                    + "CASE WHEN p.owner_id IS NOT NULL THEN 'PERSONAL' WHEN d.department_id IS NOT NULL THEN 'DEPARTMENT' ELSE 'PLATFORM' END AS source "
                    + "FROM campus_current_member m LEFT JOIN campus_department_policy d ON d.department_id=m.department_id "
                    + "LEFT JOIN campus_user_usage_policy p ON p.owner_id=m.owner_id AND p.user_id=m.user_id AND p.department_id=m.department_id WHERE m.owner_id=?",member.get("owner_id"));
            var policy=policies.get(0);
            boolean free=Boolean.TRUE.equals(policy.get("free"));
            String funding=(String)policy.get("funding");
            boolean pooled="DEPARTMENT".equals(funding);
            boolean controlled=(pooled&&!free) || policy.get("cost_limit")!=null || policy.get("token_limit")!=null || policy.get("department_tokens")!=null;
            LocalDate period=month();
            var totals=usage(department,null,period);
            var personal=usage(department,identity.userId(),period);
            boolean valueLimited=policy.get("cost_limit")!=null;
            BigDecimal valueRemaining=valueLimited?decimal(policy.get("cost_limit")).subtract(number(personal,"value")):null;
            if(valueLimited) {
                if(number(personal,"unknown_active_value").signum()>0)throw exhausted("存在金额尚未确认的进行中调用，请先结算或核对后继续使用金额预算");
                if(valueRemaining.signum()<=0)throw exhausted("个人月度使用金额上限已耗尽");
                if(!usablePricing(pricing.getPrice(model,channel)))throw exhausted("模型缺少可确认的计价规则，不能使用金额预算调用");
            }
            jdbc.update("INSERT INTO campus_department_account(department_id) VALUES (?) ON CONFLICT DO NOTHING",department);
            ReservationEstimate estimate=controlled ? estimate(service,model,channel,request,free) : new ReservationEstimate(BigDecimal.ZERO,0);
            if(controlled&&!estimate.remainingBudget()&&discounts!=null) {
                var quote=discounts.calculate(identity,model,channel);
                BigDecimal multiplier=quote.finalDiscountRate().max(BigDecimal.ONE);
                var body=json.valueToTree(request);
                BigDecimal units="vidGen".equals(service)?body.path("duration").decimalValue():Set.of("imgGen","imgEdit").contains(service)?BigDecimal.valueOf(body.path("n").asLong(body.path("options").path("n").asLong(1))):BigDecimal.valueOf(estimate.tokens()).multiply(BigDecimal.valueOf(4)).movePointLeft(6);
                BigDecimal adjustment=quote.fixedAdjustment().max(BigDecimal.ZERO).multiply(units);
                estimate=new ReservationEstimate(estimate.cost().add(adjustment).multiply(multiplier).setScale(8,RoundingMode.UP),estimate.tokens());
            }
            BigDecimal reservedValue=controlled?estimate.cost():null;
            if(estimate.remainingBudget()) {
                var pool=jdbc.queryForMap("SELECT balance-reserved_amount AS available FROM campus_department_account WHERE department_id=?",department);
                BigDecimal cost=valueLimited?valueRemaining:!free&&pooled?number(pool,"available"):BigDecimal.ZERO;
                if(!free&&pooled)cost=cost.min(number(pool,"available"));
                reservedValue=valueLimited||(!free&&pooled)?cost:null;
                BigDecimal remainingTokens=null;
                if(policy.get("department_tokens")!=null)remainingTokens=decimal(policy.get("department_tokens")).subtract(number(totals,"tokens"));
                if(policy.get("token_limit")!=null){BigDecimal own=decimal(policy.get("token_limit")).subtract(number(personal,"tokens"));remainingTokens=remainingTokens==null?own:remainingTokens.min(own);}
                if((remainingTokens!=null&&remainingTokens.signum()<=0)||((valueLimited||(!free&&pooled))&&cost.signum()<=0))throw exhausted("可用预算已耗尽或被进行中的请求预占");
                estimate=new ReservationEstimate(cost.max(BigDecimal.ZERO),remainingTokens==null?0:remainingTokens.longValueExact());
            }
            checkTokens(policy.get("department_tokens"),totals,estimate.tokens(),"部门月度 Token 预算不足");
            checkTokens(policy.get("token_limit"),personal,estimate.tokens(),"个人月度 Token 上限不足");
            if (valueLimited && number(personal,"value").add(reservedValue).compareTo(decimal(policy.get("cost_limit")))>0)
                throw exhausted("个人月度使用金额预算不足（包括正在进行的调用预占）");
            if (pooled && !free) {
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM campus_usage_reservation WHERE department_id=? AND state='RECONCILE')",Boolean.class,department)))
                    throw exhausted("部门有待核对消耗，请先核对或结算后继续使用共享余额");
                int changed=jdbc.update("UPDATE campus_department_account SET reserved_amount=reserved_amount+?,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE department_id=? AND balance-reserved_amount>=? AND balance-reserved_amount>0",estimate.cost(),department,estimate.cost());
                if(changed!=1)throw exhausted("部门共享余额不足，请联系管理员追加额度；不会转扣个人余额");
            }
            String account= Integer.valueOf(1).equals(member.get("user_type"))?(String)member.get("company_id"):identity.userId();
            Integer type=Integer.valueOf(1).equals(member.get("user_type"))?1:2;
            var snapshot=new DepartmentUsageSnapshot(UUID.randomUUID().toString(),department,(String)member.get("department_name"),funding,free,
                    (String)policy.get("source"),((Number)policy.get("version")).longValue(),account,type,period,
                    policy.get("department_tokens")==null?null:((Number)policy.get("department_tokens")).longValue(),
                    policy.get("cost_limit")==null?null:decimal(policy.get("cost_limit")),
                    policy.get("token_limit")==null?null:((Number)policy.get("token_limit")).longValue(),Objects.toString(policy.get("policy_updated_at"),null));
            jdbc.update("INSERT INTO campus_usage_reservation(request_id,user_id,owner_id,department_id,department_name,period_start,funding_source,free_usage,"
                    + "policy_source,policy_version,account_id,account_type,reserved_cost,reserved_tokens,snapshot,reserved_value) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshot.requestId(),identity.userId(),member.get("owner_id"),department,snapshot.departmentName(),period,funding,free,
                    snapshot.policySource(),snapshot.policyVersion(),account,type,free?BigDecimal.ZERO:estimate.cost(),estimate.tokens(),encode(snapshot),reservedValue);
            return identity.withUsageSnapshot(snapshot);
        });
    }

    public record ReservationEstimate(BigDecimal cost,long tokens,boolean remainingBudget) {
        public ReservationEstimate(BigDecimal cost,long tokens){this(cost,tokens,false);}
    }

    /** Conservative reservation; uncertain dimensions fail closed for controlled requests. */
    public ReservationEstimate estimate(String service,String model,String channel,Object request,boolean free) {
        if(Set.of("tts","stt").contains(service)||request==null)return new ReservationEstimate(BigDecimal.ZERO,0,true);
        JsonNode body;
        try{body=json.valueToTree(request);}catch(IllegalArgumentException cannotSerializeMedia){return new ReservationEstimate(BigDecimal.ZERO,0,true);}
        var price=pricing.getPrice(model,channel);
        if(price==null)throw exhausted("模型缺少可用于额度预占的价格");
        BigDecimal cost;
        long tokens;
        if ("vidGen".equals(service)) {
            if(!"second".equals(price.getBillingUnit()))return new ReservationEstimate(BigDecimal.ZERO,0,true);
            BigDecimal seconds=body.path("duration").decimalValue();
            if(seconds.signum()<=0)return new ReservationEstimate(BigDecimal.ZERO,0,true);
            if(seconds.compareTo(BigDecimal.valueOf(3600))>0)throw bad("视频 duration 超出可预占范围");
            // Remote input duration cannot be trusted without probing the media.
            if(body.toString().contains("video_url"))return new ReservationEstimate(BigDecimal.ZERO,0,true);
            BigDecimal unit=price.getVideoPriceRules().stream().map(ModelPricingService.ModelPricing.VideoPriceRule::price)
                    .filter(Objects::nonNull).max(BigDecimal::compareTo).orElseThrow(()->exhausted("视频价格未配置"));
            cost=unit.multiply(seconds);tokens=0;
        } else if ("imgGen".equals(service)||"imgEdit".equals(service)) {
            tokens=0;
            long count=body.path("n").asLong(body.path("options").path("n").asLong(1));
            if(count<1||count>100)throw bad("图片数量须为 1-100");
            BigDecimal unit=BigDecimal.ZERO;
            for(var rule:price.getRules()) {
                JsonNode prices=tree(rule.priceJson());
                if(prices.has("image")) unit=unit.max(prices.get("image").decimalValue());
                if(prices.has("image_count")) unit=unit.max(prices.get("image_count").decimalValue());
                if(prices.has("imagePrice")) unit=unit.max(prices.get("imagePrice").decimalValue());
            }
            if(unit.signum()<=0&&!free)return new ReservationEstimate(BigDecimal.ZERO,0,true);
            cost=unit.multiply(BigDecimal.valueOf(count));
        } else {
            String text=body.toString();
            if(text.contains("image_url")||text.contains("input_audio")||text.contains("video_url")||Set.of("tts","stt").contains(service))
                return new ReservationEstimate(BigDecimal.ZERO,0,true);
            long output=body.path("max_completion_tokens").asLong(body.path("max_tokens").asLong(0));
            boolean generates=Set.of("chat","completion").contains(service);
            if(generates&&output<1)return new ReservationEstimate(BigDecimal.ZERO,0,true);
            if(output>1_000_000)throw bad("max_tokens 或 max_completion_tokens 超出可预占范围");
            long input=text.getBytes(StandardCharsets.UTF_8).length+256L;
            long count=body.path("n").asLong(1);
            if(count<1||count>100)throw bad("生成数量须为 1-100");
            tokens=Math.addExact(input,Math.multiplyExact(output,count));
            BigDecimal unit=max(price.getInputPrice(),price.getOutputPrice(),price.getThinkingPrice(),price.getCacheCreateInputPrice(),price.getCacheHitInputPrice(),price.getCacheHitExplicitInputPrice());
            for(var tier:price.getTiers())unit=unit.max(max(tier.inputPrice(),tier.outputPrice(),tier.thinkingPrice(),tier.cacheCreateInputPrice(),tier.cacheHitInputPrice(),tier.cacheHitExplicitInputPrice()));
            for(var rule:price.getRules()) {
                var fields=tree(rule.priceJson()).fields();
                while(fields.hasNext()) {
                    var field=fields.next();
                    if(field.getValue().isNumber())unit=unit.max(field.getValue().decimalValue().divide(BigDecimal.valueOf(1_000_000),14,RoundingMode.UP));
                }
            }
            // Include separate thinking/cache charges and fixed adjustments conservatively.
            cost=unit.multiply(BigDecimal.valueOf(tokens)).multiply(BigDecimal.valueOf(4));
        }
        return new ReservationEstimate(cost.max(BigDecimal.ZERO).setScale(8,RoundingMode.UP),tokens);
    }

    public void release(DepartmentUsageSnapshot snapshot) {
        if(snapshot==null)return;
        tx.executeWithoutResult(status->{
            lock(snapshot.departmentId());
            var rows=jdbc.queryForList("SELECT * FROM campus_usage_reservation WHERE request_id=? FOR UPDATE",snapshot.requestId());
            if(rows.isEmpty()||!"RESERVED".equals(rows.get(0).get("state")))return;
            if(snapshot.departmentFunded()&&!snapshot.freeUsage())jdbc.update("UPDATE campus_department_account SET reserved_amount=GREATEST(0,reserved_amount-?) WHERE department_id=?",rows.get(0).get("reserved_cost"),snapshot.departmentId());
            jdbc.update("UPDATE campus_usage_reservation SET state='RELEASED',reserved_cost=0,reserved_tokens=0,reserved_value=0,updated_at=CURRENT_TIMESTAMP WHERE request_id=?",snapshot.requestId());
        });
    }

    public void unconfirmed(DepartmentUsageSnapshot snapshot,Long bill,String error) {
        if(snapshot==null)return;
        jdbc.update("UPDATE campus_usage_reservation SET state='RECONCILE',actual_cost=reserved_cost,actual_value=reserved_value,actual_tokens=reserved_tokens,billing_record_id=COALESCE(?,billing_record_id,(SELECT id FROM ai_billing_record WHERE billing_request_id=?)),error_message=?,updated_at=CURRENT_TIMESTAMP "
                + "WHERE request_id=? AND state='RESERVED'",bill,snapshot.requestId(),error==null?"用量或结算尚未确认":error.substring(0,Math.min(1000,error.length())),snapshot.requestId());
        if(bill!=null)jdbc.update("UPDATE ai_billing_record SET settlement_status='RECONCILE',actual_deducted=NULL WHERE id=?",bill);
    }

    public boolean retrySettlement(String requestId) {
        var rows=jdbc.queryForList("SELECT r.snapshot,b.id,b.total_cost,b.usage_value,b.total_tokens,b.service_type,r.error_message FROM campus_usage_reservation r JOIN ai_billing_record b ON b.id=r.billing_record_id OR b.billing_request_id=r.request_id WHERE r.request_id=?",requestId);
        if(rows.isEmpty()||rows.get(0).get("error_message")!=null)throw bad("此记录需先核对上游真实用量，不能直接重试扣费");
        var row=rows.get(0);
        if(Set.of("chat","embedding","rerank").contains(Objects.toString(row.get("service_type"),""))&&decimal(row.get("total_tokens")).signum()<=0)
            throw bad("Token 用量尚未确认，不能直接按零用量结算");
        return settle(BillingRecordEntity.builder().id(((Number)row.get("id")).longValue()).totalCost(decimal(row.get("total_cost"))).usageValue(row.get("usage_value")==null?null:decimal(row.get("usage_value")))
                .totalTokens(row.get("total_tokens")==null?0:((Number)row.get("total_tokens")).longValue()).build(),decode((String)row.get("snapshot")));
    }

    /** Settlement and its receipt commit together; duplicate callbacks return the same receipt. */
    public boolean settle(BillingRecordEntity record,DepartmentUsageSnapshot snapshot) {
        return Boolean.TRUE.equals(tx.execute(status->{
            lock(snapshot.departmentId());
            var row=jdbc.queryForMap("SELECT * FROM campus_usage_reservation WHERE request_id=? FOR UPDATE",snapshot.requestId());
            if("SETTLED".equals(row.get("state")))return true;
            BigDecimal cost=record.getTotalCost()==null?BigDecimal.ZERO:record.getTotalCost();
            BigDecimal value=record.getUsageValue()!=null?record.getUsageValue():!snapshot.freeUsage()&&!Boolean.TRUE.equals(record.getIsFreeQuota())?cost.max(BigDecimal.ZERO):null;
            long tokens=record.getTotalTokens()==null?0:record.getTotalTokens();
            if(value==null&&snapshot.memberCostLimit()!=null) {
                unconfirmed(snapshot,record.getId(),"使用金额尚未确认，保留金额预占等待核对");return false;
            }
            boolean pooled=snapshot.departmentFunded();
            if(pooled&&!snapshot.freeUsage()) {
                int changed=jdbc.update("UPDATE campus_department_account SET balance=balance-?,reserved_amount=GREATEST(0,reserved_amount-?),updated_at=CURRENT_TIMESTAMP "
                        + "WHERE department_id=? AND balance-(reserved_amount-?)>=?",cost,row.get("reserved_cost"),snapshot.departmentId(),row.get("reserved_cost"),cost);
                if(changed!=1) {
                    jdbc.update("UPDATE campus_usage_reservation SET state='RECONCILE',actual_cost=?,actual_value=?,actual_tokens=?,billing_record_id=?,updated_at=CURRENT_TIMESTAMP WHERE request_id=?",
                            cost,value,tokens,record.getId(),snapshot.requestId());
                    jdbc.update("UPDATE ai_billing_record SET settlement_status='RECONCILE',actual_deducted=0 WHERE id=?",record.getId());
                    return false;
                }
            }
            jdbc.update("UPDATE campus_usage_reservation SET state='SETTLED',actual_cost=?,actual_value=?,actual_tokens=?,billing_record_id=?,updated_at=CURRENT_TIMESTAMP WHERE request_id=?",
                    cost,value,tokens,record.getId(),snapshot.requestId());
            if(pooled || cost.signum()==0)jdbc.update("UPDATE ai_billing_record SET settlement_status=?,actual_deducted=? WHERE id=?",
                    cost.signum()==0?"EXEMPT":"SETTLED",pooled?cost:BigDecimal.ZERO,record.getId());
            refreshAlerts(snapshot.departmentId());
            return true;
        }));
    }

    public void bindTask(DepartmentUsageSnapshot snapshot,String taskNo) {
        if(snapshot!=null)jdbc.update("UPDATE campus_usage_reservation SET task_no=? WHERE request_id=? AND task_no IS NULL",taskNo,snapshot.requestId());
    }
    public DepartmentUsageSnapshot decode(String value) {
        if(value==null)return null;
        try{return json.readValue(value,DepartmentUsageSnapshot.class);}catch(Exception e){throw new IllegalStateException("使用规则快照损坏",e);}
    }
    public String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("无法保存管理快照",e);}
    }
    public JsonNode tree(String value) {
        try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("管理数据解析失败",e);}
    }
    public void lock(String department) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"department:"+department);
    }
    public Map<String,Object> usage(String department,String user,LocalDate period) {
        String where=" FROM campus_usage_reservation WHERE department_id=? AND period_start=? AND state IN ('RESERVED','SETTLED','RECONCILE')";
        var args=new ArrayList<Object>(List.of(department,period));
        if(user!=null){where+=" AND user_id=?";args.add(user);}
        String value="(CASE WHEN state='RESERVED' THEN reserved_value ELSE COALESCE(actual_value,CASE WHEN NOT free_usage AND actual_cost>0 THEN actual_cost END) END)";
        return jdbc.queryForMap("SELECT COALESCE(SUM(CASE WHEN state='RESERVED' THEN reserved_tokens ELSE actual_tokens END),0) AS tokens,"
                + "COALESCE(SUM(CASE WHEN state='RESERVED' THEN reserved_cost ELSE actual_cost END),0) AS cost,"
                + "COALESCE(SUM("+value+"),0) AS value,"
                + "COUNT(*) FILTER(WHERE "+value+" IS NULL) AS unknown_value,"
                + "COUNT(*) FILTER(WHERE state IN ('RESERVED','RECONCILE') AND ("+value+" IS NULL OR (state='RECONCILE' AND error_message IS NOT NULL))) AS unknown_active_value"+where,args.toArray());
    }
    public void refreshAlerts(String department) {
        var policies=jdbc.queryForList("SELECT * FROM campus_department_policy WHERE department_id=?",department);
        if(policies.isEmpty())return;
        var policy=policies.get(0);var usage=usage(department,null,month());
        var accounts=jdbc.queryForList("SELECT * FROM campus_department_account WHERE department_id=?",department);
        Integer[] thresholds;
        try{thresholds=(Integer[])((java.sql.Array)policy.get("alert_thresholds")).getArray();}catch(Exception e){throw new IllegalStateException(e);}
        for(int threshold:thresholds) {
            if(policy.get("monthly_tokens")!=null && reached(number(usage,"tokens"),decimal(policy.get("monthly_tokens")),threshold))alert(department,"TOKENS",threshold,"部门月度 Token 已使用及预占达到 "+threshold+"%");
            if(!accounts.isEmpty()) {
                var account=accounts.get(0);BigDecimal total=decimal(account.get("allocated_total"));
                if(total.signum()>0&&reached(total.subtract(decimal(account.get("balance"))),total,threshold))alert(department,"BALANCE",threshold,"部门共享金额使用达到 "+threshold+"%");
            }
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(cron="0 */5 * * * *",zone="Asia/Shanghai")
    public void updateBudgetAlerts() {
        for(String department:jdbc.queryForList("SELECT department_id FROM campus_department_policy",String.class))
            tx.executeWithoutResult(status->{lock(department);refreshAlerts(department);});
    }
    private void alert(String dept,String metric,int threshold,String message) {
        jdbc.update("INSERT INTO campus_department_alert(department_id,period_start,metric,threshold,message) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",dept,month(),metric,threshold,message);
    }
    private boolean reached(BigDecimal used,BigDecimal total,int percent){return used.multiply(BigDecimal.valueOf(100)).compareTo(total.multiply(BigDecimal.valueOf(percent)))>=0;}
    private void checkTokens(Object limit,Map<String,Object> used,long reserve,String message){if(limit!=null&&number(used,"tokens").add(BigDecimal.valueOf(reserve)).compareTo(decimal(limit))>0)throw exhausted(message);}
    public static LocalDate month(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1);}
    public static BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
    public static boolean usablePricing(ModelPricingService.ModelPricing price) {
        if(price==null)return false;
        if(price.getPriceMode()!=null)return !price.getVideoPriceRules().isEmpty();
        if(Integer.valueOf(2).equals(price.getBillingMode()))return !price.getTiers().isEmpty()&&price.getTiers().stream().allMatch(t->t.inputPrice()!=null&&t.outputPrice()!=null);
        if(Integer.valueOf(3).equals(price.getBillingMode()))return !price.getRules().isEmpty();
        return price.getInputPrice()!=null&&price.getOutputPrice()!=null;
    }
    private static BigDecimal number(Map<String,Object> values,String key){return decimal(values.get(key));}
    private static BigDecimal max(BigDecimal... values){return Arrays.stream(values).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);}
    private static ResponseStatusException exhausted(String message){return new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,message);}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
