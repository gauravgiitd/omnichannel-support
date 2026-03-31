package com.omnichannel.support.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UpsertJtbdTypeRequest(
        @NotBlank @Size(max = 256) String name,
        @Size(max = 5000) String description,
        @NotEmpty List<@Valid StageRequest> stages) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StageRequest(
            @JsonAlias("stageKey")
            @Size(max = 128) String stageKey,
            @JsonAlias("stageName")
            @NotBlank @Size(max = 256) String stageName,
            @JsonAlias("stageOrder")
            @NotNull Integer stageOrder,
            @JsonAlias("terminalCompleted")
            boolean terminalCompleted) {}
}
