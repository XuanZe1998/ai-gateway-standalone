package org.unreal.modelrouter.persistence.jpa.entity.platform;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 映射算力平台 ai_channel 表（只读）
 */
@Data
@Entity
@Table(name = "ai_channel")
public class PlatformChannelEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "type")
    private String type;

    @Column(name = "currency")
    private String currency;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "actual_url")
    private String actualUrl;

    @Column(name = "chat_completion_url")
    private String chatCompletionUrl;

    @Column(name = "completion_url")
    private String completionUrl;

    @Column(name = "embedding_url")
    private String embeddingUrl;

    @Column(name = "rerank_url")
    private String rerankUrl;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "audio_url")
    private String audioUrl;

    @Column(name = "video_url")
    private String videoUrl;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "timeout")
    private Integer timeout;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "deleted")
    private Boolean deleted;
}
