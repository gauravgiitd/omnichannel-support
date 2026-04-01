package com.omnichannel.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tasks")
@Getter
@Setter
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_number", nullable = false, unique = true, length = 32)
    private String taskNumber;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @Column(name = "issue_type", nullable = false, length = 128)
    private String issueType;

    @Column(length = 64)
    private String lob;

    @Column(name = "claim_id", length = 64)
    private String claimId;

    @Column(name = "policy_id", length = 64)
    private String policyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", length = 32)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_tier", length = 32)
    private ExecutionTier executionTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_channel", nullable = false, length = 32)
    private ChannelType sourceChannel;

    @Column(name = "assigned_queue", length = 128)
    private String assignedQueue;

    @Column(name = "assigned_agent", length = 128)
    private String assignedAgent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_jtbd_id")
    private CustomerJtbd customerJtbd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
