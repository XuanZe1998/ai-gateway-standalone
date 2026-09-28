package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** 教师账号已绑定的受管设备证书。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "teacher_trusted_device",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_teacher_device_binding_device",
                    columnNames = {"identity_binding_id", "device_id"})
        },
        indexes = @Index(name = "idx_teacher_device_binding", columnList = "identity_binding_id,status"))
public class TeacherTrustedDeviceEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "identity_binding_id", nullable = false, length = 36)
    private String identityBindingId;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "device_name", length = 128)
    private String deviceName;

    @Column(name = "certificate_fingerprint", length = 64)
    private String certificateFingerprint;

    @Column(name = "certificate_expires_at")
    private LocalDateTime certificateExpiresAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

