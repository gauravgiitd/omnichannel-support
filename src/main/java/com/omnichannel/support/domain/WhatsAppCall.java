package com.omnichannel.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "whatsapp_calls")
@Getter
@Setter
public class WhatsAppCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", nullable = false, unique = true, length = 128)
    private String callId;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "phone_number", length = 64)
    private String phoneNumber;

    @Column(name = "from_phone", length = 64)
    private String fromPhone;

    @Column(name = "to_phone", length = 64)
    private String toPhone;

    @Column(length = 64)
    private String status;

    @Column(length = 64)
    private String direction;

    @Column(length = 64)
    private String event;

    @Column(name = "permission_requested_by", length = 256)
    private String permissionRequestedBy;

    @Column(name = "permission_requested_at")
    private Instant permissionRequestedAt;

    @Column(name = "external_message_id", length = 256)
    private String externalMessageId;

    @Column(name = "raw_payload_json", columnDefinition = "TEXT")
    private String rawPayloadJson;

    @Column(name = "session_sdp_type", length = 32)
    private String sessionSdpType;

    @Column(name = "session_sdp", columnDefinition = "TEXT")
    private String sessionSdp;

    @Column(name = "phone_number_id", length = 128)
    private String phoneNumberId;

    @Column(name = "display_phone_number", length = 64)
    private String displayPhoneNumber;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
