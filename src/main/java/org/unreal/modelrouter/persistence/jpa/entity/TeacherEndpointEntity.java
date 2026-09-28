package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** 教师专属 OpenAI 兼容入口。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "teacher_endpoint", indexes =
        @Index(name = "idx_teacher_endpoint_binding", columnList = "identity_binding_id"))
public class TeacherEndpointEntity {

    @Id
    @Column(name = "endpoint_id", length = 64)
    private String endpointId;

    @Column(name = "identity_binding_id", nullable = false, length = 36)
    private String identityBindingId;

    @Column(name = "credential_id", nullable = false, unique = true, length = 64)
    private String credentialId;

    @Column(name = "permissions", nullable = false, length = 512)
    private String permissions;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
