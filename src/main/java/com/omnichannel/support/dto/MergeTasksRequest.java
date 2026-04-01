package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MergeTasksRequest(
        @NotBlank @Size(max = 32) String primaryTaskNumber,
        @NotBlank @Size(max = 32) String mergedTaskNumber,
        @Size(max = 256) String mergedByActor) {}
