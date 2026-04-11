package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WhatsAppCallControlDto(
        String callId,
        String customerId,
        String phoneNumber,
        String from,
        String to,
        String status,
        String direction,
        String event,
        String sessionSdpType,
        String sessionSdp,
        String permissionStatus,
        Instant permissionUpdatedAt,
        Instant permissionExpiresAt,
        String phoneNumberId,
        String displayPhoneNumber,
        boolean answerable,
        boolean terminable,
        Instant occurredAt) {}
