package org.unreal.modelrouter.auth.campus.key;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.unreal.modelrouter.billing.DepartmentBudgetService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@Service
public class DepartmentAdminService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final UserAdminService users;
    private final DepartmentBudgetService budgets;
    public DepartmentAdminService(JdbcTemplate jdbc,ObjectMapper json,UserAdminService users,DepartmentBudgetService budgets) {
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.json=json;this.users=users;this.budgets=budgets;
    }

    public record Filter(@org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate start,
                         @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate end,String department,String search,String departmentSearch,
                         String model,String service,Boolean freeUsage,String sort,
                         String identityType,String role,Integer accountType,Boolean enabled,Boolean currentFreeUsage) {
        public Filter(LocalDate start,LocalDate end,String department,String search,String departmentSearch,String model,String service,Boolean freeUsage,String sort) {
            this(start,end,department,search,departmentSearch,model,service,freeUsage,sort,null,null,null,null,null);
        }
        public Filter {
            validateIdentity(identityType,role,accountType);
            if(start==null)start=DepartmentBudgetService.month();
            if(end==null)end=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            if(end.isBefore(start)||end.isAfter(start.plusYears(2)))throw bad("日期范围须为有效的两年以内区间");
            for(String value:new String[]{department,search,departmentSearch,model,service})if(value!=null&&value.length()>512)throw bad("筛选条件过长");
            if(sort==null)sort="cost";
            if(!Set.of("cost","tokens","requests","name").contains(sort))throw bad("排序字段无效");
        }
        Map<String,Object> params(){
            Map<String,Object> p=new HashMap<>();p.put("start",start.atStartOfDay());p.put("end",end.plusDays(1).atStartOfDay());
            p.put("department",blank(department));p.put("search",like(search));p.put("departmentSearch",like(departmentSearch));
            p.put("model",blank(model));p.put("service",blank(service));p.put("free",freeUsage);
            p.put("identityType",blank(identityType));p.put("role",blank(role));p.put("accountType",accountType);p.put("enabled",enabled);p.put("currentFree",currentFreeUsage);return p;
        }
    }

    public static final String GLOBAL="all:members";
    private static final String DIRECTORY="""
        SELECT m.*,CASE WHEN NULLIF(BTRIM(c.type_code),'') IS NOT NULL THEN 'code:'||BTRIM(c.type_code)
                       WHEN NULLIF(BTRIM(c.type_name),'') IS NOT NULL THEN 'name:'||BTRIM(c.type_name) ELSE 'unknown' END AS identity_type,
          COALESCE(NULLIF(BTRIM(c.type_name),''),NULLIF(BTRIM(c.type_code),''),'身份未知') AS identity_name,
          COALESCE(c.roles,'') AS roles,c.enabled AS campus_enabled
        FROM campus_current_member m LEFT JOIN LATERAL (
          SELECT b.type_code,b.type_name,b.roles,b.enabled FROM campus_identity_binding b WHERE b.system_user_id=m.owner_id
          ORDER BY b.enabled DESC NULLS LAST,b.updated_at DESC NULLS LAST,b.created_at DESC NULLS LAST,b.id LIMIT 1
        ) c ON TRUE
        """;
    private static final String IDENTITY_MATCH="""
         AND (CAST(:identityType AS TEXT) IS NULL OR m.identity_type=:identityType)
         AND (CAST(:role AS TEXT) IS NULL OR :role=ANY(regexp_split_to_array(m.roles,'\\s*,\\s*')))
         AND (CAST(:accountType AS INTEGER) IS NULL OR m.user_type=:accountType)
         AND (CAST(:enabled AS BOOLEAN) IS NULL OR m.campus_enabled=:enabled)
        """;
    private static final String EFFECTIVE="""
        SELECT m.*,COALESCE(p.free_usage,d.free_usage,false) AS free_usage,
          COALESCE(p.funding_source,d.funding_source,'PERSONAL') AS funding_source,
          CASE WHEN p.owner_id IS NOT NULL THEN 'PERSONAL' WHEN d.department_id IS NOT NULL THEN 'DEPARTMENT' ELSE 'PLATFORM' END AS policy_source,
          COALESCE(p.monthly_cost,d.member_cost_limit) AS monthly_cost,COALESCE(p.monthly_tokens,d.member_token_limit) AS monthly_tokens
        FROM (
        """+DIRECTORY+"""
        ) m
        LEFT JOIN campus_department_policy d ON d.department_id=m.department_id
        LEFT JOIN campus_user_usage_policy p ON p.owner_id=m.owner_id AND p.user_id=m.user_id AND p.department_id=m.department_id
        """;

    private static final String CTE="""
        WITH members AS (
        """+EFFECTIVE+"""
        ), bills AS (
          SELECT b.*,COALESCE(b.department_id,'historical:unknown') AS dept,
            COALESCE(b.department_name,'历史归属未知') AS dept_name,
            COALESCE(b.usage_value,CASE WHEN b.total_cost>0 AND NOT b.is_admin_free_usage AND NOT COALESCE(b.is_free_quota,false) THEN b.total_cost END) AS budget_value
          FROM ai_billing_record b
          WHERE b.is_deleted=false AND b.started_at>=:start AND b.started_at<:end
            AND (CAST(:department AS TEXT) IS NULL OR COALESCE(b.department_id,'historical:unknown')=:department)
            AND (CAST(:model AS TEXT) IS NULL OR b.model_name=:model)
            AND (CAST(:service AS TEXT) IS NULL OR b.service_type=:service)
            AND (CAST(:free AS BOOLEAN) IS NULL OR b.is_admin_free_usage=:free)
            AND ((CAST(:identityType AS TEXT) IS NULL AND CAST(:role AS TEXT) IS NULL AND CAST(:accountType AS INTEGER) IS NULL AND CAST(:enabled AS BOOLEAN) IS NULL AND CAST(:currentFree AS BOOLEAN) IS NULL)
              OR EXISTS (SELECT 1 FROM members m WHERE m.user_id=b.user_id
        """+IDENTITY_MATCH+"""
                 AND (CAST(:currentFree AS BOOLEAN) IS NULL OR m.free_usage=:currentFree)))
            AND (COALESCE(b.user_account,b.user_id,'') ILIKE :search OR EXISTS (SELECT 1 FROM members m WHERE m.user_id=b.user_id AND (m.display_name ILIKE :search OR m.account ILIKE :search)))
        ), usage AS (
          SELECT dept,MAX(dept_name) AS dept_name,user_id,COUNT(*) AS requests,
            COALESCE(SUM(total_tokens),0) AS tokens,COALESCE(SUM(total_cost),0) AS cost,
            COALESCE(SUM(actual_deducted),0) AS deducted,
            COALESCE(SUM(budget_value),0) AS usage_value,COUNT(*) FILTER(WHERE budget_value IS NULL) AS value_unknown,
            COUNT(*) FILTER(WHERE settlement_status IN ('PENDING','RECONCILE','FAILED','UNKNOWN')) AS pending,
            COALESCE(SUM(normal_input_tokens),0) AS input_tokens,COALESCE(SUM(normal_output_tokens),0) AS output_tokens,
            COALESCE(SUM(COALESCE(cache_hit_explicit_tokens,0)+COALESCE(cache_create_explicit_tokens,0)+COALESCE(cache_hit_tokens,0)),0) AS cache_tokens
          FROM bills GROUP BY dept,user_id
        ), rows AS (
          SELECT COALESCE(m.department_id,u.dept) AS id,COALESCE(u.dept_name,m.department_name) AS name,
            COALESCE(m.user_id,u.user_id) AS user_id,COALESCE(m.account,(SELECT cm.account FROM members cm WHERE cm.user_id=u.user_id LIMIT 1),u.user_id) AS account,
            COALESCE(m.display_name,(SELECT cm.display_name FROM members cm WHERE cm.user_id=u.user_id LIMIT 1),u.user_id) AS display_name,COALESCE(m.owner_id,(SELECT cm.owner_id FROM members cm WHERE cm.user_id=u.user_id LIMIT 1)) AS owner_id,
            m.owner_id IS NOT NULL AS current_member,COALESCE(m.free_usage,false) AS free_usage,
            m.identity_type,m.identity_name,m.roles,m.campus_enabled,m.user_type,
            COALESCE(m.funding_source,'PERSONAL') AS funding_source,COALESCE(m.policy_source,'HISTORICAL') AS policy_source,
            m.monthly_cost,m.monthly_tokens,COALESCE(u.requests,0) AS requests,COALESCE(u.tokens,0) AS tokens,
            COALESCE(u.cost,0) AS cost,COALESCE(u.deducted,0) AS deducted,COALESCE(u.pending,0) AS pending,
            COALESCE(u.usage_value,0) AS usage_value,COALESCE(u.value_unknown,0) AS value_unknown,
            COALESCE(u.input_tokens,0) AS input_tokens,COALESCE(u.output_tokens,0) AS output_tokens,COALESCE(u.cache_tokens,0) AS cache_tokens
          FROM (SELECT * FROM members m WHERE (CAST(:department AS TEXT) IS NULL OR department_id=:department)
            AND (display_name ILIKE :search OR account ILIKE :search)
            AND (CAST(:currentFree AS BOOLEAN) IS NULL OR free_usage=:currentFree)
        """+IDENTITY_MATCH+"""
          ) m
          FULL JOIN usage u ON u.dept=m.department_id AND u.user_id=m.user_id
        )
        """;

    public Map<String,Object> directoryOptions() {
        var rows=jdbc.queryForList(DIRECTORY);
        var identities=new TreeMap<String,Map<String,Object>>();var roles=new TreeSet<String>();
        for(var row:rows) {
            String id=(String)row.get("identity_type");
            identities.putIfAbsent(id,Map.of("value",id,"label",row.get("identity_name")));
            for(String role:Objects.toString(row.get("roles"),"").split(","))if(!role.isBlank())roles.add(role.trim());
        }
        return Map.of("identities",identities.values(),"roles",roles);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> report(Filter filter,boolean members,int page,int size) {
        if(page<0||page>100000||size<1||size>100)throw bad("分页无效");
        var p=filter.params();String source=source(members);
        Long total=named.queryForObject(CTE+"SELECT COUNT(*) FROM ("+source+") result",p,Long.class);
        Map<String,Object> summary=named.queryForMap(CTE+"SELECT COALESCE(SUM(tokens),0) AS tokens,COALESCE(SUM(cost),0) AS cost,"
                + "COALESCE(SUM(deducted),0) AS deducted,COALESCE(SUM(requests),0) AS requests,COUNT(DISTINCT user_id) FILTER(WHERE requests>0) AS active,"
                + "COUNT(*) FILTER(WHERE current_member) AS members,COUNT(*) FILTER(WHERE current_member AND requests=0) AS unused,COALESCE(SUM(pending),0) AS pending,COALESCE(SUM(usage_value),0) AS usage_value,COALESCE(SUM(value_unknown),0) AS value_unknown FROM rows WHERE name ILIKE :departmentSearch OR id ILIKE :departmentSearch",p);
        p.put("limit",size);p.put("offset",page*size);
        String order=switch(filter.sort()){case "tokens"->"tokens DESC";case "requests"->"requests DESC";case "name"->members?"display_name":"name";default->"cost DESC";};
        var records=named.queryForList(CTE+"SELECT * FROM ("+source+") result ORDER BY "+order+",id"+(members?",user_id":"")+" LIMIT :limit OFFSET :offset",p);
        if(!members)records.forEach(this::enrichDepartment);
        else records.stream().filter(r->Boolean.TRUE.equals(r.get("current_member"))).forEach(r->r.put("budgetUsage",budgets.usage((String)r.get("id"),(String)r.get("user_id"),DepartmentBudgetService.month())));
        return Map.of("records",records,"total",total==null?0:total,"summary",summary,"start",filter.start(),"end",filter.end());
    }
    private String source(boolean members) {
        if(members)return "SELECT * FROM rows WHERE name ILIKE :departmentSearch OR id ILIKE :departmentSearch";
        return "SELECT id,MAX(name) AS name,COUNT(*) FILTER(WHERE current_member) AS user_count,COUNT(*) FILTER(WHERE current_member AND free_usage) AS free_user_count,"
                + "COUNT(*) FILTER(WHERE requests>0) AS active,COUNT(*) FILTER(WHERE current_member AND requests=0) AS unused_count, SUM(requests) AS requests,SUM(tokens) AS tokens,SUM(cost) AS cost,SUM(deducted) AS deducted,SUM(pending) AS pending "
                + ",SUM(usage_value) AS usage_value,SUM(value_unknown) AS value_unknown FROM rows WHERE name ILIKE :departmentSearch OR id ILIKE :departmentSearch GROUP BY id";
    }
    private void enrichDepartment(Map<String,Object> row) {
        String id=(String)row.get("id");
        var policies=jdbc.queryForList("SELECT * FROM campus_department_policy WHERE department_id=?",id);
        row.put("policy",policies.isEmpty()?Map.of():policyView(policies.get(0)));
        row.put("account",account(id));row.put("monthlyUsage",budgets.usage(id,null,DepartmentBudgetService.month()));
        row.put("alerts",alerts(id));
    }
    private Map<String,Object> policyView(Map<String,Object> policy) {
        var result=new LinkedHashMap<>(policy);
        try{result.put("alert_thresholds",((java.sql.Array)policy.get("alert_thresholds")).getArray());}catch(Exception e){throw new IllegalStateException(e);}
        return result;
    }
    public Map<String,Object> account(String department) {
        var rows=jdbc.queryForList("SELECT balance,allocated_total,reserved_amount,balance-reserved_amount AS available FROM campus_department_account WHERE department_id=?",department);
        return rows.isEmpty()?Map.of("balance",0,"allocated_total",0,"reserved_amount",0,"available",0):rows.get(0);
    }
    private Map<String,Object> configuration(String department) {
        var policies=jdbc.queryForList("SELECT * FROM campus_department_policy WHERE department_id=?",department);
        return Map.of("account",account(department),"policy",policies.isEmpty()?Map.of():policyView(policies.get(0)));
    }
    public List<Map<String,Object>> alerts(String department) {
        return department==null?jdbc.queryForList("SELECT * FROM campus_department_alert WHERE period_start=? ORDER BY created_at DESC",DepartmentBudgetService.month()):
                jdbc.queryForList("SELECT * FROM campus_department_alert WHERE department_id=? AND period_start=? ORDER BY created_at DESC",department,DepartmentBudgetService.month());
    }
    public Map<String,Object> department(String department) {
        var result=new LinkedHashMap<String,Object>();result.put("id",department);enrichDepartment(result);
        result.put("operations",operations(department));
        result.put("reconciliation",jdbc.queryForList("SELECT r.request_id,r.user_id,r.department_name,r.funding_source,r.state,r.reserved_cost,r.reserved_value,r.reserved_tokens,r.actual_cost,r.actual_value,r.actual_tokens,COALESCE(r.billing_record_id,b.id) AS billing_record_id,r.task_no,r.error_message,r.created_at,b.settlement_status "
                + "FROM campus_usage_reservation r LEFT JOIN ai_billing_record b ON b.id=r.billing_record_id OR b.billing_request_id=r.request_id WHERE (?='all:members' OR r.department_id=?) AND (r.state IN ('RESERVED','RECONCILE') OR b.settlement_status IN ('PENDING','FAILED','RECONCILE')) ORDER BY r.created_at DESC LIMIT 100",department,department));
        return result;
    }
    @Transactional
    public Map<String,Object> retrySettlement(String request,String reason,String operator) {
        if(reason==null||reason.isBlank()||reason.length()>500)throw bad("请填写核对原因");
        var rows=jdbc.queryForList("SELECT department_id,funding_source FROM campus_usage_reservation WHERE request_id=?",request);
        if(rows.isEmpty()||!"DEPARTMENT".equals(rows.get(0).get("funding_source")))throw bad("此操作仅支持部门共享池待结算记录");
        boolean settled=budgets.retrySettlement(request);
        var result=Map.<String,Object>of("settled",settled,"requestId",request);
        jdbc.update("INSERT INTO campus_admin_batch(id,operator,department_id,kind,payload,targets,fingerprint,status,reason,result,completed_at) VALUES (?,?,?,?,?,'[]','','COMPLETED',?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),operator,rows.get(0).get("department_id"),"RECONCILE",budgets.encode(Map.of("requestId",request)),reason,budgets.encode(result));
        return result;
    }
    public List<Map<String,Object>> operations(String department) {
        return jdbc.queryForList("SELECT id,kind,operator,status,reason,result,created_at,completed_at FROM campus_admin_batch WHERE (department_id=? OR EXISTS (SELECT 1 FROM jsonb_array_elements(CAST(targets AS JSONB)) t WHERE t->>'department_id'=?)) AND status<>'PREVIEW' ORDER BY created_at DESC LIMIT 100",department,department);
    }
    public Map<String,Object> user(long owner,Filter filter,int billPage,int billSize) {
        if(billPage<0||billPage>100000||billSize<1||billSize>100)throw bad("账单分页无效");
        var member=jdbc.queryForList("SELECT * FROM ("+EFFECTIVE+") m WHERE owner_id=?",owner);
        if(member.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        var p=filter.params();p.put("user",member.get(0).get("user_id"));
        p.put("billLimit",billSize);p.put("billOffset",billPage*billSize);
        String where=" WHERE user_id=:user";
        var detail=new LinkedHashMap<String,Object>();detail.put("profile",member.get(0));
        detail.put("identity",users.detail(owner));
        detail.put("budgetUsage",budgets.usage((String)member.get(0).get("department_id"),(String)p.get("user"),DepartmentBudgetService.month()));
        detail.put("trends",named.queryForList(CTE+"SELECT CAST(started_at AS DATE) AS date,COALESCE(SUM(total_tokens),0) AS tokens,COALESCE(SUM(total_cost),0) AS cost,COALESCE(SUM(budget_value),0) AS usage_value,COUNT(*) AS requests FROM bills"+where+" GROUP BY CAST(started_at AS DATE) ORDER BY date",p));
        detail.put("models",named.queryForList(CTE+"SELECT model_name,service_type,COALESCE(SUM(total_tokens),0) AS tokens,COALESCE(SUM(total_cost),0) AS cost,COUNT(*) AS requests FROM bills"+where+" GROUP BY model_name,service_type ORDER BY cost DESC",p));
        detail.put("keys",named.queryForList(CTE+"SELECT api_key_id,MAX(api_key_name) AS name,COALESCE(SUM(total_tokens),0) AS tokens,COALESCE(SUM(total_cost),0) AS cost FROM bills"+where+" GROUP BY api_key_id ORDER BY cost DESC",p));
        detail.put("bills",named.queryForList(CTE+"SELECT id,started_at,model_name,service_type,total_tokens,total_cost,budget_value AS usage_value,actual_deducted,settlement_status,department_id,department_name,funding_source,is_admin_free_usage,usage_detail FROM bills"+where+" ORDER BY started_at DESC,id DESC LIMIT :billLimit OFFSET :billOffset",p));
        detail.put("billTotal",named.queryForObject(CTE+"SELECT COUNT(*) FROM bills"+where,p,Long.class));
        detail.put("quota",jdbc.queryForList("SELECT total_quota,used_quota,remaining_quota,period_start,period_end FROM ai_user_free_quota WHERE user_id=? AND deleted=false",p.get("user")));
        detail.put("account",jdbc.queryForList("SELECT b.account_id,b.account_type,b.balance FROM ai_account_balance b WHERE b.account_id=? AND b.account_type=? AND b.deleted=false",
                Integer.valueOf(1).equals(member.get(0).get("user_type"))?member.get(0).get("company_id"):p.get("user"),Integer.valueOf(1).equals(member.get(0).get("user_type"))?1:2));
        return detail;
    }

    public String csv(Filter filter,boolean members) {
        var rows=named.queryForList(CTE+source(members)+" ORDER BY id"+(members?",user_id":"")+" LIMIT 100001",filter.params());
        if(rows.size()>100000)throw bad("导出超过 10 万行，请缩小日期范围");
        var output=new StringBuilder("\uFEFF部门标识,部门,用户账号,姓名,成员状态,Token,账单费用（元）,已确认扣费（元）,账单记录数,待核对记录数,使用金额（预算计量元）,计价未知记录数\r\n");
        for(var row:rows)output.append(String.join(",",Arrays.asList(row.get("id"),row.get("name"),row.get("account"),row.get("display_name"),row.get("current_member"),row.get("tokens"),row.get("cost"),row.get("deducted"),row.get("requests"),row.get("pending"),row.get("usage_value"),row.get("value_unknown")).stream().map(DepartmentAdminService::csvCell).toList())).append("\r\n");
        return output.toString();
    }

    public record BatchRequest(String departmentId,String kind,List<Long> ownerIds,String search,Boolean selectionFreeUsage,Boolean freeUsage,
           String fundingSource,BigDecimal amount,Long monthlyTokens,BigDecimal monthlyCost,Long memberTokens,
           List<Integer> alertThresholds,boolean overwriteExceptions,String reason,
           String identityType,String role,Integer accountType,Boolean enabled,String departmentSearch) {
        public BatchRequest(String departmentId,String kind,List<Long> ownerIds,String search,Boolean selectionFreeUsage,Boolean freeUsage,
                String fundingSource,BigDecimal amount,Long monthlyTokens,BigDecimal monthlyCost,Long memberTokens,List<Integer> alertThresholds,boolean overwriteExceptions,String reason) {
            this(departmentId,kind,ownerIds,search,selectionFreeUsage,freeUsage,fundingSource,amount,monthlyTokens,monthlyCost,memberTokens,alertThresholds,overwriteExceptions,reason,null,null,null,null,null);
        }
        BatchRequest withOwners(List<Long> owners) {
            return new BatchRequest(departmentId,kind,owners,search,selectionFreeUsage,freeUsage,fundingSource,amount,monthlyTokens,monthlyCost,memberTokens,alertThresholds,overwriteExceptions,reason,identityType,role,accountType,enabled,departmentSearch);
        }
        BatchRequest forAudit(List<Long> owners) {
            return new BatchRequest(departmentId,kind,owners,null,null,freeUsage,fundingSource,amount,monthlyTokens,monthlyCost,memberTokens,alertThresholds,overwriteExceptions,reason,null,null,null,null,null);
        }
    }

    private void lockScope(BatchRequest request) {
        budgets.lock(request.departmentId());
        if(GLOBAL.equals(request.departmentId())) {
            // Freeze membership first, then lock departments in the same order as CAS transfers.
            targets(request).stream().map(t->(String)t.get("department_id")).distinct().sorted().forEach(budgets::lock);
        }
    }

    @Transactional
    public Map<String,Object> preview(BatchRequest request,String operator) {
        validate(request);lockScope(request);
        var targets=targets(request);
        if(GLOBAL.equals(request.departmentId()))request=request.withOwners(targets.stream().map(t->((Number)t.get("owner_id")).longValue()).toList());
        if(targets.isEmpty()&&!Set.of("POOL_TOPUP","DEPARTMENT_POLICY").contains(request.kind()))throw bad("没有匹配的当前成员");
        var payload=json.valueToTree(request);
        String fingerprint=fingerprint(request,targets);
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO campus_admin_batch(id,operator,department_id,kind,payload,targets,fingerprint,reason) VALUES (?,?,?,?,?,?,?,?)",
                id,operator,request.departmentId(),request.kind(),budgets.encode(request),budgets.encode(targets),fingerprint,request.reason().trim());
        var accounts=new LinkedHashMap<String,Map<String,Object>>();
        for(var target:targets) {
            if(!"ALLOCATE".equals(request.kind()))break;
            String account=(String)target.get("allocation_account");Integer type=((Number)target.get("allocation_type")).intValue();
            accounts.putIfAbsent(type+":"+account,Map.of("accountId",account,"accountType",type,"shared",type==1,"balance",target.get("balance")));
        }
        if("POOL_TOPUP".equals(request.kind()))accounts.put(request.departmentId(),Map.of("accountId",request.departmentId(),"accountType",3,"shared",true,"balance",account(request.departmentId()).get("balance")));
        return Map.of("id",id,"targets",targets,"count",targets.size(),"accounts",accounts.values(),
                "totalAmount",request.amount()==null?BigDecimal.ZERO:request.amount().multiply(BigDecimal.valueOf("POOL_TOPUP".equals(request.kind())?1:accounts.size())),
                "payload",payload,"expiresInMinutes",30,"departmentBefore",configuration(request.departmentId()));
    }

    @Transactional
    public Map<String,Object> commit(String id,String operator) {
        var batches=jdbc.queryForList("SELECT * FROM campus_admin_batch WHERE id=? FOR UPDATE",id);
        if(batches.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"批次不存在");
        var batch=batches.get(0);
        if(!operator.equals(batch.get("operator")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅预览操作人可提交此批次");
        if("COMPLETED".equals(batch.get("status")))return json.convertValue(budgets.tree((String)batch.get("result")),Map.class);
        if(!Set.of("PREVIEW","FAILED").contains(batch.get("status")))throw conflict("批次状态不可提交");
        if(((java.sql.Timestamp)batch.get("created_at")).toLocalDateTime().isBefore(java.time.LocalDateTime.now().minusMinutes(30)))throw conflict("预览已过期，请重新预览");
        BatchRequest request;
        try{request=json.readValue((String)batch.get("payload"),BatchRequest.class);}catch(Exception e){throw new IllegalStateException(e);}
        lockScope(request);
        var targets=targets(request);
        if(!fingerprint(request,targets).equals(batch.get("fingerprint")))throw conflict("部门成员、规则、额度或到账账户已变化，请重新预览");
        // Lock all selected users and affected accounts in a stable order; legacy allocation uses the same locks.
        for(var target:targets)jdbc.queryForObject("SELECT id FROM sldd_system_users WHERE id=? FOR UPDATE",Long.class,target.get("owner_id"));
        targets.stream().map(t->t.get("allocation_type")+":"+t.get("allocation_account")).distinct().sorted().forEach(account->
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,account));
        if(!fingerprint(request,targets(request)).equals(batch.get("fingerprint")))throw conflict("目标已变化，请重新预览");
        var departmentBefore=configuration(request.departmentId());
        Set<String> credited=new HashSet<>();
        if("ALLOCATE".equals(request.kind())) {
            for(var target:targets) {
                String key=target.get("allocation_type")+":"+target.get("allocation_account");
                users.allocate(((Number)target.get("owner_id")).longValue(),credited.add(key)?request.amount():BigDecimal.ZERO,request.monthlyTokens(),operator);
            }
        } else if(Set.of("POLICY","FREE_LIMITED").contains(request.kind())) {
            for(var target:targets)setUserPolicy(target,request,operator);
        } else if("RESET_POLICY".equals(request.kind())) {
            for(var target:targets)jdbc.update("DELETE FROM campus_user_usage_policy WHERE owner_id=? AND department_id=?",target.get("owner_id"),target.get("department_id"));
        } else if("POOL_TOPUP".equals(request.kind())) {
            jdbc.update("INSERT INTO campus_department_account(department_id,balance,allocated_total) VALUES (?,?,?) ON CONFLICT(department_id) DO UPDATE "
                    + "SET balance=campus_department_account.balance+EXCLUDED.balance,allocated_total=campus_department_account.allocated_total+EXCLUDED.allocated_total,updated_at=CURRENT_TIMESTAMP",
                    request.departmentId(),request.amount(),request.amount());
        } else if("DEPARTMENT_POLICY".equals(request.kind())) {
            jdbc.update("INSERT INTO campus_department_policy(department_id,free_usage,funding_source,monthly_tokens,member_cost_limit,member_token_limit,alert_thresholds,reason,updated_by) "
                    + "VALUES (?,?,?,?,?,?,CAST(? AS INTEGER[]),?,?) ON CONFLICT(department_id) DO UPDATE SET free_usage=EXCLUDED.free_usage,funding_source=EXCLUDED.funding_source,"
                    + "monthly_tokens=EXCLUDED.monthly_tokens,member_cost_limit=EXCLUDED.member_cost_limit,member_token_limit=EXCLUDED.member_token_limit,alert_thresholds=EXCLUDED.alert_thresholds,"
                    + "reason=EXCLUDED.reason,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP,version=campus_department_policy.version+1",
                    request.departmentId(),request.freeUsage(),request.fundingSource(),request.monthlyTokens(),request.monthlyCost(),request.memberTokens(),array(request.alertThresholds()),request.reason(),operator);
            if(request.overwriteExceptions())for(var target:targets)jdbc.update("DELETE FROM campus_user_usage_policy WHERE owner_id=? AND department_id=?",target.get("owner_id"),request.departmentId());
        }
        Map<String,Object> result=Map.of("id",id,"status","COMPLETED","success",targets.size(),"failed",0,"accountsCredited",credited.size(),"before",targets,"after",targets(request.forAudit(targets.stream().map(t->((Number)t.get("owner_id")).longValue()).toList())),"payload",request,
                "departmentBefore",departmentBefore,"departmentAfter",configuration(request.departmentId()));
        jdbc.update("UPDATE campus_admin_batch SET status='COMPLETED',result=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",budgets.encode(result),id);
        budgets.refreshAlerts(request.departmentId());
        return result;
    }

    private void setUserPolicy(Map<String,Object> target,BatchRequest request,String operator) {
        jdbc.update("INSERT INTO campus_user_usage_policy(owner_id,user_id,department_id,free_usage,funding_source,monthly_cost,monthly_tokens,reason,updated_by) VALUES (?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(owner_id) DO UPDATE SET user_id=EXCLUDED.user_id,department_id=EXCLUDED.department_id,free_usage=EXCLUDED.free_usage,funding_source=EXCLUDED.funding_source,"
                + "monthly_cost=EXCLUDED.monthly_cost,monthly_tokens=EXCLUDED.monthly_tokens,reason=EXCLUDED.reason,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP",
                target.get("owner_id"),target.get("user_id"),target.get("department_id"),request.freeUsage(),request.fundingSource(),request.monthlyCost(),request.memberTokens(),request.reason(),operator);
    }
    @Transactional
    public void recordFailure(String id,String operator,String error) {
        jdbc.update("UPDATE campus_admin_batch SET status='FAILED',result=? WHERE id=? AND operator=? AND status IN ('PREVIEW','FAILED')",
                budgets.encode(Map.of("error",Objects.toString(error,"操作失败"),"failed",true,"rolledBack",true)),id,operator);
    }
    private List<Map<String,Object>> targets(BatchRequest request) {
        var p=new HashMap<String,Object>();p.put("department",GLOBAL.equals(request.departmentId())?null:request.departmentId());p.put("search",like(request.search()));
        p.put("identityType",blank(request.identityType()));p.put("role",blank(request.role()));p.put("accountType",request.accountType());p.put("enabled",request.enabled());p.put("departmentSearch",like(request.departmentSearch()));
        String where=" WHERE (CAST(:department AS TEXT) IS NULL OR m.department_id=:department) AND (m.display_name ILIKE :search OR m.account ILIKE :search) AND (m.department_name ILIKE :departmentSearch OR m.department_id ILIKE :departmentSearch)"+IDENTITY_MATCH;
        if(request.ownerIds()!=null&&!request.ownerIds().isEmpty()){p.put("ids",request.ownerIds());where+=" AND m.owner_id IN (:ids)";}
        if(request.selectionFreeUsage()!=null){p.put("selectionFree",request.selectionFreeUsage());where+=" AND COALESCE(p.free_usage,d.free_usage,false)=:selectionFree";}
        var result=named.queryForList("SELECT m.*,CASE WHEN m.user_type=1 THEN m.company_id ELSE m.user_id END AS allocation_account,CASE WHEN m.user_type=1 THEN 1 ELSE 2 END AS allocation_type,"
                + "COALESCE(b.balance,0) AS balance,q.total_quota,q.used_quota,p.free_usage AS exception_free,p.funding_source AS exception_funding,p.monthly_cost AS exception_cost,p.monthly_tokens AS exception_tokens,"
                + "d.member_cost_limit AS inherited_cost,d.member_token_limit AS inherited_tokens,COALESCE(d.version,0) AS department_version FROM ("+DIRECTORY+") m "
                + "LEFT JOIN ai_account_balance b ON b.account_id=CASE WHEN m.user_type=1 THEN m.company_id ELSE m.user_id END AND b.account_type=CASE WHEN m.user_type=1 THEN 1 ELSE 2 END AND b.deleted=false "
                + "LEFT JOIN ai_user_free_quota q ON q.user_id=m.user_id AND q.deleted=false "
                + "LEFT JOIN campus_user_usage_policy p ON p.owner_id=m.owner_id AND p.user_id=m.user_id AND p.department_id=m.department_id "
                + "LEFT JOIN campus_department_policy d ON d.department_id=m.department_id"+where+" ORDER BY m.owner_id",p);
        if(result.size()>10000)throw bad("单批最多 10000 人，请分批操作");
        if(request.ownerIds()!=null&&!request.ownerIds().isEmpty()&&result.size()!=new HashSet<>(request.ownerIds()).size())throw conflict("所选成员已离开部门或不符合筛选条件");
        if("ALLOCATE".equals(request.kind()))for(var target:result)if(target.get("allocation_account")==null)throw bad("成员缺少计费账户，请先补齐身份");
        // Include defaults and pool state even for an empty department, to detect concurrent edits.
        return result;
    }
    private String fingerprint(BatchRequest request,List<Map<String,Object>> targets) {
        var stable=targets.stream().map(target->{var copy=new LinkedHashMap<>(target);copy.remove("balance");copy.remove("used_quota");return copy;}).toList();
        var state=Map.of("targets",stable,"policy",jdbc.queryForList("SELECT department_id,version,updated_by,reason FROM campus_department_policy WHERE department_id=?",request.departmentId()));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(state)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private void validate(BatchRequest r) {
        if(r.departmentId()==null||r.departmentId().isBlank()||r.departmentId().length()>512||r.departmentId().equals("historical:unknown"))throw bad("请选择当前部门");
        if(r.reason()==null||r.reason().isBlank()||r.reason().length()>500)throw bad("请填写 1-500 字的操作原因");
        if(r.kind()==null||!Set.of("ALLOCATE","POLICY","FREE_LIMITED","RESET_POLICY","POOL_TOPUP","DEPARTMENT_POLICY").contains(r.kind()))throw bad("操作类型无效");
        validateIdentity(r.identityType(),r.role(),r.accountType());
        if(r.departmentSearch()!=null&&r.departmentSearch().length()>128)throw bad("部门搜索条件过长");
        if(GLOBAL.equals(r.departmentId())&&!Set.of("ALLOCATE","POLICY","FREE_LIMITED","RESET_POLICY").contains(r.kind()))throw bad("跨部门只支持个人规则与额度操作");
        if(r.search()!=null&&r.search().length()>128)throw bad("搜索条件过长");
        if(r.ownerIds()!=null&&r.ownerIds().size()>10000)throw bad("选择人数过多");
        if(r.ownerIds()!=null&&(r.ownerIds().isEmpty()||r.ownerIds().stream().anyMatch(id->id==null||id<=0)))throw bad("请勾选有效成员，或明确选择全部符合筛选的成员");
        if(Set.of("POLICY","FREE_LIMITED","DEPARTMENT_POLICY").contains(r.kind())&&(r.freeUsage()==null||!Set.of("PERSONAL","DEPARTMENT").contains(Objects.toString(r.fundingSource(),""))))throw bad("请选择使用方式与额度来源");
        if("FREE_LIMITED".equals(r.kind())&&(!Boolean.TRUE.equals(r.freeUsage())||(r.memberTokens()==null&&r.monthlyCost()==null)))throw bad("免费并限量须启用免费使用，并至少填写每人每月 Token 或使用金额上限");
        if(Set.of("ALLOCATE","POOL_TOPUP").contains(r.kind())) {
            validateAmount(r.amount());
            if("POOL_TOPUP".equals(r.kind())&&r.amount().signum()==0)throw bad("追加金额须大于零");
            if("ALLOCATE".equals(r.kind())&&r.amount().signum()==0&&r.monthlyTokens()==null)throw bad("请填写追加金额或月度免费 Token 总额");
        }
        if(r.monthlyCost()!=null)validateAmount(r.monthlyCost());
        validateTokens(r.monthlyTokens());validateTokens(r.memberTokens());
        if(r.alertThresholds()!=null&&(r.alertThresholds().isEmpty()||r.alertThresholds().size()>10||r.alertThresholds().stream().anyMatch(v->v==null||v<1||v>100)))throw bad("提醒阈值须为 1-100 的百分比");
        if(GLOBAL.equals(r.departmentId()))return;
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM campus_current_member WHERE department_id=?",Long.class,r.departmentId());
        if(count==null||count==0) {
            Long known=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM campus_department_policy WHERE department_id=?)+(SELECT COUNT(*) FROM campus_department_account WHERE department_id=?)",Long.class,r.departmentId(),r.departmentId());
            if(!Set.of("POOL_TOPUP","DEPARTMENT_POLICY").contains(r.kind())||known==null||known==0)throw bad("该部门没有当前成员");
        }
    }
    private static void validateIdentity(String identity,String role,Integer accountType) {
        if((identity!=null&&identity.length()>512)||(role!=null&&role.length()>128))throw bad("身份筛选条件过长");
        if(accountType!=null&&!Set.of(1,2).contains(accountType))throw bad("计费账户类型无效");
    }
    private String array(List<Integer> values){return "{"+String.join(",",(values==null?List.of(80,90,100):values).stream().distinct().sorted().map(String::valueOf).toList())+"}";}
    private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String like(String value){return "%"+(value==null?"":value.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_"))+"%";}
    private static String csvCell(Object value){String text=Objects.toString(value,"");String trimmed=text.stripLeading();if((!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0)||(!text.isEmpty()&&"\t\r\n".indexOf(text.charAt(0))>=0))text="'"+text;return "\""+text.replace("\"","\"\"")+"\"";}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static void validateAmount(BigDecimal value) { if(value==null||value.signum()<0||value.scale()>6||value.compareTo(new BigDecimal("100000000"))>0)throw bad("金额须为 0-1 亿之间的非负数，最多六位小数"); }
    private static void validateTokens(Long value) { if(value!=null&&(value<0||value>1_000_000_000_000L))throw bad("Token 总额须在 0-1 万亿之间"); }

}
