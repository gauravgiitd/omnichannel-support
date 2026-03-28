package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.SenderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PostMessageRequest(
        @NotNull ChannelType channel,
        @NotNull SenderType senderType,
        @NotBlank @Size(max = 512) String senderIdentifier,
        @NotBlank @Size(max = 50_000) String body,
        List<@Size(max = 2048) String> attachmentUrls,
        @Size(max = 512) String externalThreadRef,
        Map<String, Object> metadata) {}
