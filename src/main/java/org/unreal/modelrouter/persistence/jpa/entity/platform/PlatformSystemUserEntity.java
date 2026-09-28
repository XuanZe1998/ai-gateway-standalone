package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 映射 sldd_system_users 表；CAS 首次登录可创建独立命名空间的本地用户。
 * 用于通过 user_id 查找 company_id，进而关联 ai_enterprise。
 */
@Data
@Entity
@Table(name = "sldd_system_users")
public class PlatformSystemUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "campus_system_user_id")
    @SequenceGenerator(name = "campus_system_user_id", sequenceName = "campus_system_user_id_seq", initialValue = 2000000000, allocationSize = 1)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "company_id")
    private String companyId;

    @Column(name = "username")
    private String username;

    @Column(name = "mobile")
    private String mobile;

    @Column(name = "user_type")
    private Integer userType;

    @Column(name = "verify_status")
    private Integer verifyStatus;
}
