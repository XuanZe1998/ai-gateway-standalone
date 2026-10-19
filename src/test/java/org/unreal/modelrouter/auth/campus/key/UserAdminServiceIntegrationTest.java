package org.unreal.modelrouter.auth.campus.key;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "USER_ADMIN_TEST_JDBC_URL", matches = ".+")
class UserAdminServiceIntegrationTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(System.getenv("USER_ADMIN_TEST_JDBC_URL"), "postgres", "test"); }
        @Bean JdbcTemplate jdbc(DataSource data) { return new JdbcTemplate(data); }
        @Bean PlatformTransactionManager transactions(DataSource data) { return new DataSourceTransactionManager(data); }
        @Bean UserAdminService users(JdbcTemplate jdbc) { return new UserAdminService(jdbc); }
    }
    @Test void allocatesBalanceAndPersistentMonthlyQuotaWithoutLosingUsage() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var users = context.getBean(UserAdminService.class);
            jdbc.execute("""
                CREATE TABLE sldd_system_users (id BIGINT PRIMARY KEY, user_id TEXT, username TEXT, mobile TEXT, user_type INT, verify_status INT, company_id TEXT);
                CREATE TABLE campus_identity_binding (id BIGSERIAL PRIMARY KEY, system_user_id BIGINT, user_account TEXT, display_name TEXT, account TEXT, local_account TEXT,
                  staff_no TEXT, department_code TEXT, department_name TEXT, type_code TEXT, type_name TEXT, roles TEXT, enabled BOOL, created_at TIMESTAMP, updated_at TIMESTAMP);
                CREATE TABLE ai_account_balance (id BIGSERIAL PRIMARY KEY, account_id TEXT, account_type INT, balance NUMERIC, warn_enabled BOOL,
                  creator TEXT, updater TEXT, create_time TIMESTAMP, update_time TIMESTAMP, deleted BOOL);
                CREATE TABLE ai_user_free_quota (user_id TEXT PRIMARY KEY, total_quota BIGINT, used_quota BIGINT, remaining_quota BIGINT, quota_tier TEXT,
                  period_start TIMESTAMP, period_end TIMESTAMP, trial_exhausted BOOL, exhausted_at TIMESTAMP, create_time TIMESTAMP, update_time TIMESTAMP,
                  creator TEXT, updater TEXT, deleted BOOL, version BIGINT);
                INSERT INTO sldd_system_users VALUES (1,'personal','Student','123',2,2,NULL),(2,'enterprise-user','Staff','456',1,4,'company');
                INSERT INTO campus_identity_binding (system_user_id,user_account,display_name,department_name) VALUES (1,'student01','Student','Physics');
                """);
            jdbc.execute("CREATE TABLE ai_billing_record(id BIGINT,started_at TIMESTAMP,is_deleted BOOLEAN)");
            jdbc.execute("CREATE TABLE ai_video_task(id BIGINT)");
            jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/postgresql/V12__campus_gateway_keys.sql")));
            jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/postgresql/V20__campus_user_portal.sql")));
            jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/postgresql/V26__department_governance.sql")));
            users.allocate(1, new BigDecimal("10.5"), 1000L, "admin");
            assertEquals(0, jdbc.queryForObject("SELECT balance FROM ai_account_balance WHERE account_id='personal'", BigDecimal.class).compareTo(new BigDecimal("10.5")));
            jdbc.update("UPDATE ai_user_free_quota SET used_quota=400,remaining_quota=600 WHERE user_id='personal'");
            users.allocate(1, BigDecimal.ONE, 500L, "admin");
            assertEquals(100, jdbc.queryForObject("SELECT remaining_quota FROM ai_user_free_quota", Long.class));
            users.allocate(1, BigDecimal.ZERO, 200L, "admin");
            assertTrue(jdbc.queryForObject("SELECT trial_exhausted FROM ai_user_free_quota", Boolean.class));
            users.allocate(1, BigDecimal.ZERO, 2000L, "admin");
            assertFalse(jdbc.queryForObject("SELECT trial_exhausted FROM ai_user_free_quota", Boolean.class));
            assertEquals(1600, jdbc.queryForObject("SELECT remaining_quota FROM ai_user_free_quota", Long.class));
            var method = org.unreal.modelrouter.billing.freequota.FreeQuotaRepository.class.getMethod("updateQuotaPolicy", String.class, long.class, String.class, String.class);
            var sql = method.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
            int changed = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).update(sql,
                    Map.of("userId", "personal", "total", 1000000, "tier", "STUDENT", "updater", "system"));
            assertEquals(0, changed);
            users.allocate(2, BigDecimal.TEN, null, "admin");
            assertEquals("company", jdbc.queryForObject("SELECT account_id FROM ai_account_balance WHERE account_type=1", String.class));
            assertEquals(2L, users.list("", 0, 20).get("total"));
            assertEquals(1L, users.list("student01", 0, 20).get("total"));
            var bound = (java.util.List<Map<String, Object>>) users.list("student01", 0, 20).get("records");
            assertEquals(true, bound.get(0).get("campusBound"));
            var unbound = (java.util.List<Map<String, Object>>) users.list("Staff", 0, 20).get("records");
            assertEquals(false, unbound.get(0).get("campusBound"));
            assertEquals(0L, users.list("%", 0, 20).get("total"));
            var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i=0; i<8; i++) jobs.add(pool.submit(() -> users.allocate(1, BigDecimal.ONE, null, "admin")));
                for (var job : jobs) job.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            assertEquals(0, jdbc.queryForObject("SELECT balance FROM ai_account_balance WHERE account_id='personal'", BigDecimal.class).compareTo(new BigDecimal("19.5")));
            jdbc.update("UPDATE ai_user_free_quota SET period_end=CURRENT_TIMESTAMP-INTERVAL '1 month'");
            users.allocate(1, BigDecimal.ZERO, 500L, "admin");
            assertEquals(500, jdbc.queryForObject("SELECT remaining_quota FROM ai_user_free_quota", Long.class));
            // Counts are computed before pagination, and equal names with different codes stay distinct.
            jdbc.update("INSERT INTO sldd_system_users VALUES (3,'staff-2','Staff Two','789',1,4,'company'),(4,'staff-3','Staff Three','000',2,2,NULL)");
            jdbc.update("INSERT INTO campus_identity_binding(system_user_id,department_code,department_name) VALUES (2,'D1','School'),(3,'D2','School'),(4,'D1','School')");
            assertEquals(3L, users.departments("", 0, 1).get("total"));
            assertEquals(1, ((java.util.List<?>) users.departments("", 0, 1).get("records")).size());
            assertEquals(2L, users.list("", "code:D1", 0, 20).get("total"));
            assertEquals(1L, users.list("", "code:D2", 0, 20).get("total"));
            assertEquals(0L, users.list("Staff Two", "code:D1", 0, 20).get("total"));
            assertEquals(0L, users.departments("%", 0, 20).get("total"));
            users.setFreeUsage(2, true, "Teaching staff", "admin");
            var policy = new org.unreal.modelrouter.billing.UserUsagePolicyService(jdbc);
            assertTrue(policy.isFreeUsage("enterprise-user"));
            assertFalse(policy.isFreeUsage("staff-2")); // Same shared balance, different individual grant.
            var groups = (java.util.List<Map<String, Object>>) users.departments("D1", 0, 20).get("records");
            assertEquals(1L, groups.get(0).get("freeUserCount"));
            users.setFreeUsage(2, false, null, "admin"); // Optional reason may be omitted.
            assertFalse(policy.isFreeUsage("enterprise-user"));
            assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM campus_security_event WHERE event_type='USAGE_POLICY_CHANGED'", Long.class));
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> users.setFreeUsage(999, true, "", "admin"));
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> users.setFreeUsage(2, null, "", "admin"));
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> users.setFreeUsage(2, true, "a".repeat(501), "admin"));
            // Old disabled provider identities must not duplicate users or create stale departments.
            jdbc.update("UPDATE campus_identity_binding SET enabled=true,updated_at=CURRENT_TIMESTAMP");
            jdbc.update("INSERT INTO campus_identity_binding(system_user_id,department_code,department_name,enabled,updated_at) VALUES (2,'OLD','Old school',false,CURRENT_TIMESTAMP+INTERVAL '1 day')");
            assertEquals(3L, users.departments("", 0, 20).get("total"));
            assertEquals(2L, users.list("", "code:D1", 0, 20).get("total"));
            assertEquals(4L, users.list("", 0, 20).get("total"));
            assertEquals(0L, users.departments("Old school", 0, 20).get("total"));
            jdbc.update("DELETE FROM campus_identity_binding WHERE system_user_id=1");
            var unassigned = (java.util.List<Map<String, Object>>) users.departments("未分配部门", 0, 20).get("records");
            assertEquals("unassigned", unassigned.get(0).get("id"));
            assertEquals(1L, users.list("", "unassigned", 0, 20).get("total"));
        }
    }
}
