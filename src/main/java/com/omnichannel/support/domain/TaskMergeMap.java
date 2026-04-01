package com.omnichannel.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "task_merge_map")
@Getter
@Setter
public class TaskMergeMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "primary_task_id", nullable = false)
    private Task primaryTask;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merged_task_id", nullable = false)
    private Task mergedTask;

    @Column(name = "merged_at", nullable = false)
    private Instant mergedAt;

    @Column(name = "merged_by_actor", length = 256)
    private String mergedByActor;

    @PrePersist
    void onCreate() {
        if (mergedAt == null) {
            mergedAt = Instant.now();
        }
    }
}
