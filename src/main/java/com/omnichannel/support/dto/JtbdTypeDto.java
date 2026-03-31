package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JtbdTypeDto(
        String publicId,
        String name,
        String description,
        List<JtbdStageDto> stages,
        Instant createdAt,
        Instant updatedAt) {}
