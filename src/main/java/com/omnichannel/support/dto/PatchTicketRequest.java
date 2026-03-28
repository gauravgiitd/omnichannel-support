package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.TicketStatus;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PatchTicketRequest(
        @Size(max = 128) String issueType,
        @Size(max = 64) String lob,
        @Size(max = 64) String claimId,
        @Size(max = 64) String policyId,
        @Size(max = 128) String assignedQueue,
        @Size(max = 128) String assignedAgent,
        TicketStatus status) {}
