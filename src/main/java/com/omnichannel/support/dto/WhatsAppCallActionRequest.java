package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WhatsAppCallActionRequest(
        @Size(max = 32) String sdpType,
        @Size(max = 100_000) String sdp,
        @NotBlank @Size(max = 64) String action) {}
