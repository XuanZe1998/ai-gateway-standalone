// 文件说明：测试 PersistenceSchemaContractTest 的预期行为与异常场景，防止相关功能回归。
package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation")
class PersistenceSchemaContractTest {

    @Test
    void auditEntitiesUseSchemaUniqueIndexNames() {
        Set<String> configAuditIndexes = indexNames(ConfigAuditLogEntity.class);
        Set<String> securityAuditIndexes = indexNames(SecurityAuditEventEntity.class);

        Set<String> duplicateNames = configAuditIndexes.stream()
                .filter(securityAuditIndexes::contains)
                .collect(Collectors.toSet());

        assertTrue(duplicateNames.isEmpty(),
                () -> "PostgreSQL index names must be unique within a schema: " + duplicateNames);
    }

    private Set<String> indexNames(final Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        return Arrays.stream(table.indexes())
                .map(Index::name)
                .collect(Collectors.toSet());
    }
}
