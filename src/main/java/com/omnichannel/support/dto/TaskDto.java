package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.TaskPriority;
import com.omnichannel.support.domain.TaskStatus;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskDto(
        String taskId,
        String customerId,
        String customerJtbdId,
        String customerJtbdTypeName,
        String customerJtbdStageName,
        String customerJtbdStatus,
        String issueType,
        String lob,
        String claimId,
        String policyId,
        TaskStatus status,
        TaskPriority priority,
        ChannelType sourceChannel,
        String assignedQueue,
        String assignedAgent,
        Instant createdAt,
        Instant updatedAt) {}
