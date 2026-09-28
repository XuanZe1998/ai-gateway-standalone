package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射算力平台 ai_api_key 表（只读）
 * 注意：算力平台不存储明文 API Key，而是存储 key_hash（SHA-256）+ key_prefix + key_suffix
 */
@Data
@Entity
@Table(name = "ai_api_key")
public class PlatformApiKeyEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_account")
    private String userAccount;

    @Column(name = "key_hash")
    private String keyHash;

    @Column(name = "key_prefix")
    private String keyPrefix;

    @Column(name = "key_suffix")
    private String keySuffix;

    @Column(name = "description")
    private String description;

    /** 状态: 1=正常, 0=禁用, 2=体验标志（均可直接使用） */
    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_TRIAL = 2;

    @Column(name = "status")
    private Integer status;

    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "deleted")
    private Boolean deleted;
}
