package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerVisibleJtbdDto(
        String jtbdId,
        String jtbdTypeName,
        String stageName,
        String status,
        boolean completed) {}
