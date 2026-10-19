package org.unreal.modelrouter.auth.campus.key;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.unreal.modelrouter.auth.security.model.UserIdentity;
import org.unreal.modelrouter.billing.*;
import org.unreal.modelrouter.persistence.jpa.entity.BillingRecordEntity;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="DEPARTMENT_TEST_JDBC_URL",matches=".+")
class DepartmentGovernanceIntegrationTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean ModelPricingService pricing(){var p=mock(ModelPricingService.class);when(p.getPrice(anyString(),nullable(String.class))).thenReturn(new ModelPricingService.ModelPricing("model",null,new BigDecimal("0.001"),new BigDecimal("0.002")));return p;}
        @Bean UserAdminService users(JdbcTemplate j){return new UserAdminService(j);}
        @Bean DepartmentBudgetService budgets(JdbcTemplate j,ObjectMapper json,ModelPricingService p,PlatformTransactionManager tx){return new DepartmentBudgetService(j,json,p,tx);}
        @Bean DepartmentAdminService admin(JdbcTemplate j,ObjectMapper json,UserAdminService users,DepartmentBudgetService budgets){return new DepartmentAdminService(j,json,users,budgets);}
    }
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private DepartmentAdminService admin;
    private DepartmentBudgetService budgets;
    @BeforeEach void setUp() throws Exception {
        String url=System.getenv("DEPARTMENT_TEST_JDBC_URL");
        String schema="department_test_"+UUID.randomUUID().toString().replace("-","");
        var bootstrap=new JdbcTemplate(new DriverManagerDataSource(url,"postgres","test"));
        bootstrap.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres","test");
        context=new AnnotationConfigApplicationContext();context.registerBean(DataSource.class,()->ds);context.register(Config.class);context.refresh();
        jdbc=context.getBean(JdbcTemplate.class);admin=context.getBean(DepartmentAdminService.class);budgets=context.getBean(DepartmentBudgetService.class);
        jdbc.execute("""
          CREATE TABLE sldd_system_users(id BIGINT PRIMARY KEY,user_id TEXT UNIQUE,username TEXT,mobile TEXT,user_type INT,verify_status INT,company_id TEXT);
          CREATE TABLE campus_identity_binding(id BIGSERIAL PRIMARY KEY,system_user_id BIGINT,user_account TEXT,display_name TEXT,account TEXT,local_account TEXT,staff_no TEXT,department_code TEXT,department_name TEXT,type_code TEXT,type_name TEXT,roles TEXT,enabled BOOL,created_at TIMESTAMP,updated_at TIMESTAMP);
          CREATE TABLE ai_account_balance(id BIGSERIAL PRIMARY KEY,account_id TEXT,account_type INT,balance NUMERIC,warn_enabled BOOL,creator TEXT,updater TEXT,create_time TIMESTAMP,update_time TIMESTAMP,deleted BOOL);
          CREATE UNIQUE INDEX balance_unique ON ai_account_balance(account_id,account_type) WHERE deleted=false;
          CREATE TABLE ai_user_free_quota(user_id VARCHAR(64) PRIMARY KEY,total_quota BIGINT,used_quota BIGINT,remaining_quota BIGINT,quota_tier TEXT,period_start TIMESTAMP,period_end TIMESTAMP,trial_exhausted BOOL,exhausted_at TIMESTAMP,create_time TIMESTAMP,update_time TIMESTAMP,creator VARCHAR(64),updater VARCHAR(64),deleted BOOL,version BIGINT);
          CREATE TABLE campus_gateway_key(key_id TEXT PRIMARY KEY);
          CREATE TABLE campus_security_event(id BIGSERIAL PRIMARY KEY,owner_id BIGINT,event_type TEXT,description TEXT);
          CREATE TABLE ai_video_task(id BIGSERIAL PRIMARY KEY);
          CREATE TABLE ai_billing_record(id BIGSERIAL PRIMARY KEY,user_id TEXT,user_account TEXT,api_key_id TEXT,api_key_name TEXT,started_at TIMESTAMP,is_deleted BOOL DEFAULT false,model_name TEXT,service_type TEXT,total_tokens BIGINT,total_cost NUMERIC,is_free_quota BOOL DEFAULT false,normal_input_tokens BIGINT,normal_output_tokens BIGINT,cache_hit_tokens BIGINT,cache_create_explicit_tokens BIGINT,cache_hit_explicit_tokens BIGINT,usage_detail JSONB);
          INSERT INTO sldd_system_users VALUES(1,'u1','Alice',NULL,2,2,NULL),(2,'u2','Bob',NULL,1,4,'company'),(3,'u3','Carol',NULL,1,4,'company'),(4,'u4','Dave',NULL,2,2,NULL);
          INSERT INTO campus_identity_binding(system_user_id,user_account,display_name,department_code,department_name,enabled) VALUES(1,'alice','Alice','D1','学院',true),(2,'bob','Bob','D1','学院',true),(3,'carol','Carol','D1','学院',true),(4,'dave','Dave','D2','学院',true);
          """);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/postgresql/V26__department_governance.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/postgresql/V27__usage_value_limits.sql")));
    }
    @AfterEach void close(){if(context!=null)context.close();}
    private DepartmentAdminService.BatchRequest batch(String kind,BigDecimal amount,Boolean free,String funding,Long tokens){
        return new DepartmentAdminService.BatchRequest("code:D1",kind,null,"",null,free,funding,amount,tokens,null,null,List.of(80,90,100),false,"测试管理操作");
    }
    private Map<String,Object> run(DepartmentAdminService.BatchRequest r){return admin.commit((String)admin.preview(r,"admin").get("id"),"admin");}
    private DepartmentAdminService.BatchRequest limited(String identity,Boolean currentFree) {
        return new DepartmentAdminService.BatchRequest(DepartmentAdminService.GLOBAL,"FREE_LIMITED",null,"",currentFree,true,"PERSONAL",null,null,null,100L,null,false,"教师免费限量",identity,"TEACHER",2,true,null);
    }
    private void identities() {
        jdbc.update("UPDATE campus_identity_binding SET type_code='T',type_name='教师',roles='USER,TEACHER' WHERE system_user_id IN (1,3,4)");
        jdbc.update("UPDATE campus_identity_binding SET type_code='S',type_name='学生',roles='USER,STUDENT' WHERE system_user_id=2");
        jdbc.update("UPDATE campus_identity_binding SET enabled=false WHERE system_user_id=3");
    }
    private void monetaryLimit(BigDecimal amount,Long tokens) {
        run(new DepartmentAdminService.BatchRequest("code:D1","FREE_LIMITED",List.of(1L),null,null,true,"DEPARTMENT",null,null,amount,tokens,null,false,"免费金额预算"));
    }

    @Test void freeMonetaryBudgetExhaustsWithoutChargingPoolAndRecordsUsageValueSeparately() {
        monetaryLimit(BigDecimal.TEN,null);run(batch("POOL_TOPUP",new BigDecimal("100"),null,null,null));
        var snapshot=budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)).usageSnapshot();
        assertTrue(snapshot.freeUsage());
        assertTrue(jdbc.queryForObject("SELECT reserved_value>0 AND reserved_cost=0 FROM campus_usage_reservation WHERE request_id=?",Boolean.class,snapshot.requestId()));
        Long id=jdbc.queryForObject("INSERT INTO ai_billing_record(user_id,started_at,total_tokens,total_cost,usage_value,department_id,department_name,is_admin_free_usage) VALUES('u1',CURRENT_TIMESTAMP,100,0,10,'code:D1','学院',true) RETURNING id",Long.class);
        var bill=BillingRecordEntity.builder().id(id).totalTokens(100L).totalCost(BigDecimal.ZERO).usageValue(BigDecimal.TEN).build();
        assertTrue(budgets.settle(bill,snapshot));assertTrue(budgets.settle(bill,snapshot));
        assertEquals(0,BigDecimal.TEN.compareTo((BigDecimal)budgets.usage("code:D1","u1",DepartmentBudgetService.month()).get("value")));
        assertEquals(0,new BigDecimal("100").compareTo((BigDecimal)admin.account("code:D1").get("balance")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",1)));
        var summary=(Map<?,?>)admin.report(filter("code:D1"),true,0,100).get("summary");
        assertEquals(0,((BigDecimal)summary.get("cost")).signum());assertEquals(0,BigDecimal.TEN.compareTo((BigDecimal)summary.get("usage_value")));
        assertTrue(admin.csv(filter("code:D1"),true).contains("使用金额（预算计量元）"));
        assertEquals("EXEMPT",jdbc.queryForObject("SELECT settlement_status FROM ai_billing_record WHERE id=?",String.class,id));
    }

    @Test void concurrentFreeValueReservationsCannotSpendTheSameMonetaryAllowance() throws Exception {
        monetaryLimit(BigDecimal.TEN,100000L);
        var executor=Executors.newFixedThreadPool(8);var accepted=new CopyOnWriteArrayList<UserIdentity>();
        try {
            var work=new ArrayList<Future<?>>();for(int i=0;i<20;i++)work.add(executor.submit(()->{try{accepted.add(budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)));}catch(org.springframework.web.server.ResponseStatusException exhausted){assertEquals(402,exhausted.getStatusCode().value());}}));
            for(var task:work)task.get(10,TimeUnit.SECONDS);
        } finally {executor.shutdownNow();}
        assertFalse(accepted.isEmpty());assertTrue(accepted.size()<20);
        assertTrue(((BigDecimal)budgets.usage("code:D1","u1",DepartmentBudgetService.month()).get("value")).compareTo(BigDecimal.TEN)<=0);
        for(var id:accepted)budgets.release(id.usageSnapshot());
        assertEquals(0,((BigDecimal)budgets.usage("code:D1","u1",DepartmentBudgetService.month()).get("value")).signum());
        monetaryLimit(BigDecimal.ZERO,null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"imgGen","model",null,Map.of("n",1)));
    }

    @Test void unboundedRequestsHoldRemainingValueAndUnknownActiveUsageBlocksNewAmountBudgets() {
        var old=budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10));
        monetaryLimit(BigDecimal.TEN,null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",1)));
        budgets.release(old.usageSnapshot());
        var current=budgets.admit(identity("u1",1),"chat","model",null,Map.of("messages",List.of()));
        assertEquals(0,BigDecimal.TEN.compareTo((BigDecimal)budgets.usage("code:D1","u1",DepartmentBudgetService.month()).get("value")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",1)));
        budgets.unconfirmed(current.usageSnapshot(),null,"未收到完整用量");
        monetaryLimit(new BigDecimal("100"),null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",1)));
    }

    @Test void monetaryReservationsIncludePositivePriceAdjustmentsAndRejectUnpricedModels() {
        monetaryLimit(BigDecimal.TEN,null);
        var adjustment=mock(DiscountCalculationService.class);
        when(adjustment.calculate(any(),any(),any())).thenReturn(new DiscountBreakdown(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,new BigDecimal("1000000")));
        budgets.setBudgetDiscounts(adjustment);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)));
        when(context.getBean(ModelPricingService.class).getPrice("unpriced",null)).thenReturn(null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","unpriced",null,Map.of("max_tokens",10)));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM campus_usage_reservation",Long.class));
    }

    @Test void unspecifiedPersonalLimitsInheritDepartmentDefaultsAndPreviewShowsTheirEffectiveInputs() {
        run(new DepartmentAdminService.BatchRequest("code:D1","DEPARTMENT_POLICY",null,null,null,true,"PERSONAL",null,null,BigDecimal.TEN,1000L,null,false,"部门默认限额"));
        var request=new DepartmentAdminService.BatchRequest("code:D1","FREE_LIMITED",List.of(1L),null,null,true,"PERSONAL",null,null,new BigDecimal("20"),null,null,false,"仅覆盖金额");
        var preview=admin.preview(request,"admin");var targets=(List<Map<String,Object>>)preview.get("targets");
        assertEquals(1000L,((Number)targets.get(0).get("inherited_tokens")).longValue());
        admin.commit((String)preview.get("id"),"admin");
        var admitted=budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10));
        assertEquals(1000L,admitted.usageSnapshot().memberTokenLimit());
        assertEquals(0,new BigDecimal("20").compareTo(admitted.usageSnapshot().memberCostLimit()));
    }
    private UserIdentity identity(String id,long owner){return new UserIdentity(id,id,null,null,null,null,null,true,2,owner,2,true);}
    private DepartmentAdminService.Filter filter(String dept){return new DepartmentAdminService.Filter(null,null,dept,"","",null,null,null,"cost");}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("records");}

    @Test void allocationDeduplicatesCompanyAndRepeatedCommitAndPreservesUsage() {
        var request=batch("ALLOCATE",BigDecimal.TEN,null,null,1000L);
        var preview=admin.preview(request,"admin");
        assertEquals(3,preview.get("count"));assertEquals(0,new BigDecimal("20").compareTo((BigDecimal)preview.get("totalAmount")));
        String id=(String)preview.get("id");admin.commit(id,"admin");admin.commit(id,"admin");
        assertEquals(0,BigDecimal.TEN.compareTo(jdbc.queryForObject("SELECT balance FROM ai_account_balance WHERE account_id='company'",BigDecimal.class)));
        jdbc.update("UPDATE ai_user_free_quota SET used_quota=700,remaining_quota=300 WHERE user_id='u1'");
        run(batch("ALLOCATE",BigDecimal.ZERO,null,null,500L));
        assertEquals(700L,jdbc.queryForObject("SELECT used_quota FROM ai_user_free_quota WHERE user_id='u1'",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT remaining_quota FROM ai_user_free_quota WHERE user_id='u1'",Long.class));
        assertTrue(jdbc.queryForObject("SELECT trial_exhausted FROM ai_user_free_quota WHERE user_id='u1'",Boolean.class));
        assertEquals(2,admin.operations("code:D1").size());
    }

    @Test void allocationAcceptsLongCampusIdsAndAdministratorNamesAfterLegacySchemaUpgrade() {
        String user="cas:"+"a".repeat(64),operator="administrator-"+"b".repeat(80);
        jdbc.update("UPDATE sldd_system_users SET user_id=? WHERE id=1",user);
        var preview=admin.preview(batch("ALLOCATE",BigDecimal.ONE,null,null,1000L),operator);
        admin.commit((String)preview.get("id"),operator);
        assertEquals(1000L,jdbc.queryForObject("SELECT total_quota FROM ai_user_free_quota WHERE user_id=?",Long.class,user));
        assertEquals(operator,jdbc.queryForObject("SELECT updater FROM ai_user_free_quota WHERE user_id=?",String.class,user));
    }

    @Test void crossDepartmentFreeLimitUsesSameIdentityFiltersAndAuditsBothDepartments() {
        identities();
        jdbc.update("INSERT INTO ai_billing_record(user_id,started_at,total_tokens,total_cost,department_id,department_name) VALUES ('u1',CURRENT_TIMESTAMP,20,0,'code:D1','学院'),('u2',CURRENT_TIMESTAMP,90,1,'code:D1','学院')");
        var filter=new DepartmentAdminService.Filter(null,null,null,"","",null,null,null,"tokens","code:T","TEACHER",2,true,false);
        var members=admin.report(filter,true,0,1);assertEquals(2L,members.get("total"));
        assertEquals(20L,((Number)((Map<?,?>)members.get("summary")).get("tokens")).longValue());
        assertTrue(admin.csv(filter,true).contains("alice"));assertFalse(admin.csv(filter,true).contains("bob"));
        assertTrue(admin.directoryOptions().toString().contains("教师"));
        var preview=admin.preview(limited("code:T",false),"admin");assertEquals(2,preview.get("count"));
        String id=(String)preview.get("id");admin.commit(id,"admin");admin.commit(id,"admin");
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM campus_user_usage_policy WHERE free_usage AND monthly_tokens=100",Long.class));
        assertEquals("code:D1",jdbc.queryForObject("SELECT department_id FROM campus_user_usage_policy WHERE owner_id=1",String.class));
        assertEquals("code:D2",jdbc.queryForObject("SELECT department_id FROM campus_user_usage_policy WHERE owner_id=4",String.class));
        assertEquals(1,admin.operations("code:D1").size());assertEquals(1,admin.operations("code:D2").size());
        assertEquals(1,admin.operations(DepartmentAdminService.GLOBAL).size());
        run(new DepartmentAdminService.BatchRequest(DepartmentAdminService.GLOBAL,"RESET_POLICY",List.of(1L,4L),null,null,null,null,null,null,null,null,null,false,"恢复各自部门默认"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM campus_user_usage_policy",Long.class));
    }

    @Test void crossDepartmentAllocationsCreditEachUnderlyingCompanyAccountOnlyOnce() {
        jdbc.update("UPDATE campus_identity_binding SET department_code='D2' WHERE system_user_id=3");
        var request=new DepartmentAdminService.BatchRequest(DepartmentAdminService.GLOBAL,"ALLOCATE",null,null,null,null,null,BigDecimal.TEN,null,null,null,null,false,"跨部门分配");
        var preview=admin.preview(request,"admin");assertEquals(4,preview.get("count"));
        assertEquals(0,new BigDecimal("30").compareTo((BigDecimal)preview.get("totalAmount")));
        String id=(String)preview.get("id");admin.commit(id,"admin");admin.commit(id,"admin");
        assertEquals(0,BigDecimal.TEN.compareTo(jdbc.queryForObject("SELECT balance FROM ai_account_balance WHERE account_id='company'",BigDecimal.class)));
    }

    @Test void crossDepartmentPreviewFreezesNewMembersAndRejectsChangedIdentities() {
        identities();String id=(String)admin.preview(limited("code:T",false),"admin").get("id");
        jdbc.update("UPDATE campus_identity_binding SET type_code='S' WHERE system_user_id=1");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->admin.commit(id,"admin"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM campus_user_usage_policy",Long.class));
        jdbc.update("UPDATE campus_identity_binding SET type_code='T' WHERE system_user_id=1");
        String nextId=(String)admin.preview(limited("code:T",false),"admin").get("id");
        jdbc.update("INSERT INTO sldd_system_users VALUES(5,'new-teacher','New',NULL,2,2,NULL)");
        jdbc.update("INSERT INTO campus_identity_binding(system_user_id,user_account,type_code,type_name,roles,department_code,enabled) VALUES(5,'new-teacher','T','教师','USER,TEACHER','D3',true)");
        admin.commit(nextId,"admin");assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM campus_user_usage_policy WHERE owner_id=5",Long.class));
    }

    @Test void freeLimitedStopsAtHardCapWithoutConsumingMoneyOrMonthlyFreeQuota() {
        identities();run(limited("code:T",false));
        var admitted=budgets.admit(identity("u1",1),"chat","model",null,Map.of("messages",List.of(Map.of("role","user","content","hello"))));
        assertTrue(admitted.usageSnapshot().freeUsage());
        Long id=jdbc.queryForObject("INSERT INTO ai_billing_record(user_id,started_at,total_tokens,total_cost) VALUES('u1',CURRENT_TIMESTAMP,100,0) RETURNING id",Long.class);
        assertTrue(budgets.settle(BillingRecordEntity.builder().id(id).totalCost(BigDecimal.ZERO).totalTokens(100L).build(),admitted.usageSnapshot()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",1)));
        assertEquals(0,((BigDecimal)admin.account("code:D1").get("balance")).signum());
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_free_quota",Long.class));
        var invalid=new DepartmentAdminService.BatchRequest(DepartmentAdminService.GLOBAL,"FREE_LIMITED",null,null,null,true,"PERSONAL",null,null,null,null,null,false,"缺少硬上限");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->admin.preview(invalid,"admin"));
    }

    @Test void previewDetectsMembershipAndAccountChangesAndCannotBeCommittedByAnotherOperator() {
        var preview=admin.preview(batch("ALLOCATE",BigDecimal.TEN,null,null,null),"admin");
        String id=(String)preview.get("id");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->admin.commit(id,"someone-else"));
        jdbc.update("UPDATE sldd_system_users SET company_id='new-company' WHERE id=2");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->admin.commit(id,"admin"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_account_balance",Long.class));
    }

    @Test void departmentDefaultsIndividualExceptionsAndTransferExpiryAreIndependentOfBalances() {
        run(batch("DEPARTMENT_POLICY",null,true,"PERSONAL",null));
        var policies=new UserUsagePolicyService(jdbc);
        assertTrue(policies.isFreeUsage("u1"));assertFalse(policies.isFreeUsage("u4"));
        context.getBean(UserAdminService.class).setFreeUsage(1,false,"例外","admin");assertFalse(policies.isFreeUsage("u1"));
        var snapshot=budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10));
        assertFalse(snapshot.usageSnapshot().freeUsage());assertEquals("code:D1",snapshot.usageSnapshot().departmentId());
        jdbc.update("UPDATE campus_identity_binding SET department_code='D2' WHERE system_user_id=1");policies.membershipChanged(1);
        jdbc.update("UPDATE campus_identity_binding SET department_code='D1' WHERE system_user_id=1");policies.membershipChanged(1);
        assertTrue(policies.isFreeUsage("u1"));assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM campus_user_policy_history",Long.class));
        assertFalse(snapshot.usageSnapshot().freeUsage()); // Admission snapshot survives later default changes.
    }

    @Test void reportsKeepHistoricalAttributionAndMatchMembersAndCsvFilters() {
        jdbc.update("INSERT INTO ai_billing_record(user_id,user_account,started_at,model_name,service_type,total_tokens,total_cost,department_id,department_name,is_admin_free_usage,settlement_status,actual_deducted) VALUES ('u1','alice',CURRENT_TIMESTAMP,'model','chat',100,2,'code:D1','学院',false,'SETTLED',2),('u2','bob',CURRENT_TIMESTAMP,'model','chat',50,0,'code:D1','学院',true,'EXEMPT',0),('u4','dave',CURRENT_TIMESTAMP,'other','chat',20,1,NULL,NULL,false,'UNKNOWN',NULL)");
        jdbc.update("UPDATE campus_identity_binding SET department_code='D2' WHERE system_user_id=1");
        var departments=admin.report(filter(null),false,0,100);var members=admin.report(filter("code:D1"),true,0,100);
        assertEquals(0,new BigDecimal("3").compareTo((BigDecimal)((Map<?,?>)departments.get("summary")).get("usage_value"))); // Old paid writers have a reliable bill value even if the new column is absent.
        assertEquals(3L,departments.get("total"));
        assertEquals(150,rows(members).stream().mapToLong(r->((Number)r.get("tokens")).longValue()).sum());
        var alice=rows(members).stream().filter(r->"u1".equals(r.get("user_id"))).findFirst().orElseThrow();assertEquals(false,alice.get("current_member"));assertEquals(1L,alice.get("owner_id"));
        assertEquals(3L,members.get("total")); // Alice historical, plus current Bob and unused Carol.
        var freeFilter=new DepartmentAdminService.Filter(null,null,"code:D1","","","model","chat",true,"tokens");
        var free=admin.report(freeFilter,true,0,100);
        assertEquals(50L,rows(free).stream().mapToLong(r->((Number)r.get("tokens")).longValue()).sum());
        assertEquals(2,rows(free).stream().filter(r->Boolean.TRUE.equals(r.get("current_member"))).count()); // Bill-only filters never hide the management targets.
        assertTrue(admin.csv(freeFilter,true).contains("bob"));assertFalse(admin.csv(freeFilter,true).contains("alice"));
        assertEquals(1L,admin.user(1,filter("code:D1"),0,20).get("billTotal"));
    }

    @Test void concurrentReservationsStayWithinPoolAndSettlementIsIdempotent() throws Exception {
        run(batch("DEPARTMENT_POLICY",null,false,"DEPARTMENT",100000L));run(batch("POOL_TOPUP",new BigDecimal("10"),null,null,null));
        var executor=Executors.newFixedThreadPool(8);var accepted=new CopyOnWriteArrayList<UserIdentity>();
        try {
            var work=new ArrayList<Future<?>>();for(int i=0;i<20;i++)work.add(executor.submit(()->{try{accepted.add(budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)));}catch(org.springframework.web.server.ResponseStatusException expected){assertEquals(402,expected.getStatusCode().value());}}));
            for(var job:work)job.get(30,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
        assertFalse(accepted.isEmpty());assertTrue(accepted.size()<20);
        assertTrue(((BigDecimal)admin.account("code:D1").get("available")).signum()>=0);
        var first=accepted.get(0).usageSnapshot();
        Long bill=jdbc.queryForObject("INSERT INTO ai_billing_record(user_id,started_at,model_name,service_type,total_tokens,total_cost) VALUES ('u1',CURRENT_TIMESTAMP,'model','chat',20,0.5) RETURNING id",Long.class);
        var record=BillingRecordEntity.builder().id(bill).totalTokens(20L).totalCost(new BigDecimal("0.5")).build();
        assertTrue(budgets.settle(record,first));assertTrue(budgets.settle(record,first));
        assertEquals(0,new BigDecimal("9.5").compareTo((BigDecimal)admin.account("code:D1").get("balance")));
        for(int i=1;i<accepted.size();i++)budgets.release(accepted.get(i).usageSnapshot());
        assertEquals(0,((BigDecimal)admin.account("code:D1").get("reserved_amount")).signum());
        assertEquals(20L,((Number)budgets.usage("code:D1",null,DepartmentBudgetService.month()).get("tokens")).longValue());
    }

    @Test void sharedExhaustionNeverFallsBackAndFreeUsersStillConsumeTokenBudget() {
        run(batch("DEPARTMENT_POLICY",null,false,"DEPARTMENT",100000L));
        run(batch("ALLOCATE",new BigDecimal("100"),null,null,null));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)));
        run(batch("DEPARTMENT_POLICY",null,true,"DEPARTMENT",1L));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)));
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT balance FROM ai_account_balance WHERE account_id='u1'",BigDecimal.class)));
    }

    @Test void bulkSelectionIncludesOtherPagesAndRespectsCurrentFreeUsageFilter() {
        jdbc.execute("INSERT INTO sldd_system_users(id,user_id,username,user_type,verify_status) SELECT i,'bulk-'||i,'Bulk '||i,2,2 FROM generate_series(100,220) i");
        jdbc.execute("INSERT INTO campus_identity_binding(system_user_id,user_account,department_code,department_name,enabled) SELECT id,user_id,'D1','学院',true FROM sldd_system_users WHERE id>=100");
        run(batch("DEPARTMENT_POLICY",null,true,"PERSONAL",null));
        context.getBean(UserAdminService.class).setFreeUsage(1,false,"收费例外","admin");
        assertEquals(124L,admin.report(filter("code:D1"),true,0,20).get("total"));
        var request=new DepartmentAdminService.BatchRequest("code:D1","ALLOCATE",null,"",true,null,null,new BigDecimal("2"),null,null,null,null,false,"跨页分配");
        var preview=admin.preview(request,"admin");assertEquals(123,preview.get("count"));
        assertEquals(0,new BigDecimal("244").compareTo((BigDecimal)preview.get("totalAmount")));
        admin.commit((String)preview.get("id"),"admin");
        assertEquals(122L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_account_balance",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_account_balance WHERE account_id='u1'",Long.class));
    }

    @Test void unknownMultimodalSizeReservesRemainingPoolAndCancellationReleasesIt() {
        run(batch("DEPARTMENT_POLICY",null,false,"DEPARTMENT",100000L));run(batch("POOL_TOPUP",BigDecimal.TEN,null,null,null));
        var admitted=budgets.admit(identity("u1",1),"chat","model",null,Map.of("messages",List.of(Map.of("image_url","https://example.test/image.png"))));
        assertEquals(0,((BigDecimal)admin.account("code:D1").get("available")).signum());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)));
        budgets.release(admitted.usageSnapshot());budgets.release(admitted.usageSnapshot());
        assertEquals(0,BigDecimal.TEN.compareTo((BigDecimal)admin.account("code:D1").get("available")));
    }

    @Test void overEstimateCostRemainsPendingUntilTopupAndRetryCannotDoubleDebit() {
        run(batch("DEPARTMENT_POLICY",null,false,"DEPARTMENT",100000L));run(batch("POOL_TOPUP",new BigDecimal("3"),null,null,null));
        var admitted=budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10));
        Long id=jdbc.queryForObject("INSERT INTO ai_billing_record(user_id,started_at,model_name,service_type,total_tokens,total_cost) VALUES ('u1',CURRENT_TIMESTAMP,'model','chat',20,10) RETURNING id",Long.class);
        assertFalse(budgets.settle(BillingRecordEntity.builder().id(id).totalCost(BigDecimal.TEN).totalTokens(20L).build(),admitted.usageSnapshot()));
        assertEquals(0,new BigDecimal("3").compareTo((BigDecimal)admin.account("code:D1").get("balance")));
        run(batch("POOL_TOPUP",BigDecimal.TEN,null,null,null));
        assertEquals(true,admin.retrySettlement(admitted.usageSnapshot().requestId(),"已追加并核对","admin").get("settled"));
        assertEquals(true,admin.retrySettlement(admitted.usageSnapshot().requestId(),"重复重试","admin").get("settled"));
        assertEquals(0,new BigDecimal("3").compareTo((BigDecimal)admin.account("code:D1").get("balance")));
        assertEquals("SETTLED",jdbc.queryForObject("SELECT settlement_status FROM ai_billing_record WHERE id=?",String.class,id));
    }

    @Test void tokenAlertsAreUniqueAndNewMembersInheritWithoutReplayingCashAllocations() {
        run(batch("DEPARTMENT_POLICY",null,true,"PERSONAL",500L));
        run(batch("ALLOCATE",BigDecimal.TEN,null,null,null));
        jdbc.execute("INSERT INTO sldd_system_users VALUES(5,'new-user','New',NULL,2,2,NULL); INSERT INTO campus_identity_binding(system_user_id,user_account,department_code,department_name,enabled) VALUES(5,'new-user','D1','学院',true)");
        assertTrue(new UserUsagePolicyService(jdbc).isFreeUsage("new-user"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ai_account_balance WHERE account_id='new-user'",Long.class));
        var admitted=budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10));
        Long id=jdbc.queryForObject("INSERT INTO ai_billing_record(user_id,started_at,model_name,service_type,total_tokens,total_cost) VALUES ('u1',CURRENT_TIMESTAMP,'model','chat',450,0) RETURNING id",Long.class);
        var bill=BillingRecordEntity.builder().id(id).totalCost(BigDecimal.ZERO).totalTokens(450L).build();
        budgets.settle(bill,admitted.usageSnapshot());budgets.settle(bill,admitted.usageSnapshot());
        assertEquals(2,admin.alerts("code:D1").size());
        run(batch("DEPARTMENT_POLICY",null,true,"PERSONAL",450L));
        assertEquals(3,admin.alerts("code:D1").size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->budgets.admit(identity("u1",1),"chat","model",null,Map.of("max_tokens",10)));
    }
}
