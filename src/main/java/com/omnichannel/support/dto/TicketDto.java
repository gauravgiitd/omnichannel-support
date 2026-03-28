package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.TicketPriority;
import com.omnichannel.support.domain.TicketStatus;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TicketDto(
        String ticketId,
        String customerId,
        String issueType,
        String lob,
        String claimId,
        String policyId,
        TicketStatus status,
        TicketPriority priority,
        ChannelType sourceChannel,
        String assignedQueue,
        String assignedAgent,
        Instant createdAt,
        Instant updatedAt) {}
