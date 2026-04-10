package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WhatsAppCallEventDto(
        String callId,
        String from,
        String to,
        String status,
        String direction,
        String event,
        String sessionSdpType,
        boolean hasSessionSdp,
        Instant occurredAt) {}
