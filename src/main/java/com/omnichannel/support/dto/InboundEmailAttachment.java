package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InboundEmailAttachment(
        @NotBlank @Size(max = 2048) String fileUrl,
        @Size(max = 512) String fileName,
        @Size(max = 128) String mimeType,
        @Size(max = 64) String documentType) {}
