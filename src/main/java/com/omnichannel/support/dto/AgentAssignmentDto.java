package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentAssignmentDto(
        String assignedGroup,
        String assignedAgent,
        String status,
        Instant assignedAt,
        Instant closedAt) {}
