package com.omnichannel.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "customer_conversation_contexts")
@Getter
@Setter
public class CustomerConversationContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChannelType channel;

    @Column(name = "active_task_number", length = 32)
    private String activeTaskNumber;

    @Column(name = "active_customer_jtbd_public_id", length = 36)
    private String activeCustomerJtbdPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_selection_type", length = 32)
    private PendingSelectionType pendingSelectionType;

    @Column(name = "pending_options_json", columnDefinition = "TEXT")
    private String pendingOptionsJson;

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
