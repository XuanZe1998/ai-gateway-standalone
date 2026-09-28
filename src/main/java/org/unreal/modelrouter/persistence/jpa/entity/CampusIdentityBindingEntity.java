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

/** 校园统一身份与算力平台用户的绑定。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "campus_identity_binding",
        uniqueConstraints = @UniqueConstraint(name = "uk_campus_identity_provider_subject",
                columnNames = {"identity_provider", "external_subject"}),
        indexes = {
            @Index(name = "idx_campus_identity_system_user", columnList = "system_user_id"),
            @Index(name = "idx_campus_identity_account", columnList = "local_account,account")
        })
public class CampusIdentityBindingEntity {
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "identity_provider", nullable = false, length = 255)
    private String identityProvider;

    @Column(name = "identity_protocol", nullable = false, length = 16)
    private String identityProtocol;

    @Column(name = "external_subject", nullable = false, length = 255)
    private String externalSubject;

    @Column(name = "system_user_id", nullable = false)
    private Long systemUserId;

    @Column(name = "user_account", nullable = false, length = 128)
    private String userAccount;

    @Column(name = "account", length = 128)
    private String account;

    @Column(name = "local_account", length = 128)
    private String localAccount;

    @Column(name = "staff_no", length = 64)
    private String staffNo;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "type_code", nullable = false, length = 64)
    private String typeCode;

    @Column(name = "type_name", length = 128)
    private String typeName;

    @Column(name = "department_code", length = 128)
    private String departmentCode;

    @Column(name = "department_name", length = 255)
    private String departmentName;

    @Column(name = "roles", nullable = false, length = 512)
    private String roles;

    @Column(name = "user_type")
    private Integer userType;

    @Column(name = "verify_status")
    private Integer verifyStatus;

    @Column(name = "enterprise_id")
    private Long enterpriseId;

    @Column(name = "enterprise_name", length = 255)
    private String enterpriseName;

    @Column(name = "company_id", length = 128)
    private String companyId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
