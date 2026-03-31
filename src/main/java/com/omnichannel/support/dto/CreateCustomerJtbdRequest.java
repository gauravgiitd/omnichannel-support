package com.omnichannel.support.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateCustomerJtbdRequest(
        @JsonAlias("jtbdTypeId")
        @NotBlank @Size(max = 36) String jtbdTypeId,
        @JsonAlias("initialStageKey")
        @Size(max = 128) String initialStageKey) {}
