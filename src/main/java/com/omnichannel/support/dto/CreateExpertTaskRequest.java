package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateExpertTaskRequest(
        @Size(max = 36) String customerJtbdId,
        @NotBlank @Size(max = 128) String issueType,
        @Size(max = 64) String lob,
        @Size(max = 64) String claimId,
        @Size(max = 64) String policyId,
        @NotBlank @Size(max = 128) String assignedQueue,
        @NotNull TaskPriority priority,
        @NotBlank @Size(max = 20_000) String body) {}
