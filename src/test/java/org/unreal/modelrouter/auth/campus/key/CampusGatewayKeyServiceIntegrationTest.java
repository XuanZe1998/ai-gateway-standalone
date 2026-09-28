package org.unreal.modelrouter.auth.campus.key;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CAMPUS_KEY_TEST_JDBC_URL", matches = ".+")
class CampusGatewayKeyServiceIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() {
            var source = new DriverManagerDataSource(System.getenv("CAMPUS_KEY_TEST_JDBC_URL"),
                    System.getenv().getOrDefault("CAMPUS_KEY_TEST_USER", "postgres"),
                    System.getenv().getOrDefault("CAMPUS_KEY_TEST_PASSWORD", "test"));
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean CampusGatewayKeyService keys(JdbcTemplate jdbc) { return new CampusGatewayKeyService(jdbc); }
    }

    @Test
    void migrationAndConcurrentLimitAndLifecycle() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String migration = Files.readString(Path.of("src/main/resources/db/migration/postgresql/V12__campus_gateway_keys.sql"));
            for (String statement : migration.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!statement.isBlank()) jdbc.execute(statement);
            }
            var keys = context.getBean(CampusGatewayKeyService.class);
            keys.setLimit(0, 2);
            var gate = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(8);
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            try {
                for (int i = 0; i < 8; i++) {
                    futures.add(pool.submit(() -> {
                        gate.await();
                        try { keys.create(991L, "user-991", "test", false); return true; }
                        catch (IllegalStateException expected) { return false; }
                    }));
                }
                gate.countDown();
                int success = 0;
                for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) success++;
                assertEquals(2, success);
                assertEquals(2, keys.count(991L));
                var id = keys.list(991L).get(0).keyId();
                var rotated = keys.rotate(id, 991L);
                assertNotNull(keys.authenticate(rotated.secret()));
                assertThrows(IllegalArgumentException.class, () -> keys.rotate(id, 992L));
                keys.status(id, 991L, "DISABLED");
                assertNull(keys.authenticate(rotated.secret()));
                assertEquals(2, keys.count(991L));
                keys.status(id, 991L, "REVOKED");
                assertEquals(1, keys.count(991L));
                keys.create(991L, "user-991", "replacement", false);
                assertEquals(2, keys.count(991L));
            } finally { pool.shutdownNow(); }
        }
    }
}
