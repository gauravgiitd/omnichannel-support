package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateAuthenticatedTicketRequest(
        @NotBlank @Size(max = 128) String issueType,
        @Size(max = 64) String lob,
        @Size(max = 64) String claimId,
        @Size(max = 64) String policyId,
        @NotNull TicketPriority priority,
        @NotNull ChannelType sourceChannel,
        @NotBlank @Size(max = 20_000) String initialMessageBody,
        @Size(max = 512) String senderIdentifier,
        Map<String, Object> initialMessageMetadata,
        @Size(max = 512) String initialExternalThreadRef) {}
