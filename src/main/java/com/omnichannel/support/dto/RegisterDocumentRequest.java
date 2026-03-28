package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.SenderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegisterDocumentRequest(
        @NotNull ChannelType channel,
        @NotNull SenderType senderType,
        @NotBlank @Size(max = 512) String senderIdentifier,
        @NotBlank @Size(max = 2048) String fileUrl,
        @NotBlank @Size(max = 64) String documentType,
        @Size(max = 64) String claimId,
        @Size(max = 64) String policyId,
        @Size(max = 20_000) String messageBody,
        Map<String, Object> metadata) {}
