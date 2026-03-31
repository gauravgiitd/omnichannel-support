package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.JtbdInstanceStatus;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerJtbdDto(
        String publicId,
        String customerId,
        String jtbdTypeId,
        String jtbdTypeName,
        String currentStageKey,
        String currentStageName,
        JtbdInstanceStatus status,
        List<JtbdStageDto> stages,
        Instant createdAt,
        Instant updatedAt) {}
