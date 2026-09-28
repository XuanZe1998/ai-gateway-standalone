package org.unreal.modelrouter.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "model_square_content")
public class ModelSquareContentEntity {
    @Id @Column(name = "content_key", length = 300) private String contentKey;
    @Column(name = "service_type", nullable = false, length = 24) private String serviceType;
    @Column(name = "model_id", nullable = false, length = 255) private String modelId;
    @Column(name = "display_name", length = 100) private String displayName;
    @Column(name = "description", length = 2000) private String description;
    @Column(name = "tags", nullable = false, columnDefinition = "text") private String tags;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "updated_by", nullable = false, length = 255) private String updatedBy;
}
