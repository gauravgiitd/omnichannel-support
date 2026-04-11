package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InboundWhatsAppRequest(
        @NotBlank @Size(max = 32) String waMessageId,
        @NotBlank @Size(max = 32) String fromE164Phone,
        @NotBlank @Size(max = 20_000) String bodyText,
        @Size(max = 32) String replyToWaMessageId,
        @Size(max = 64) String customerIdHint,
        @Size(max = 64) String taskNumberHint,
        @Size(max = 128) String issueTypeHint,
        @Size(max = 64) String lobHint,
        @Size(max = 64) String policyIdHint,
        @Size(max = 64) String claimIdHint,
        Boolean forceNewTask,
        List<@Size(max = 2048) String> attachmentUrls,
        Map<String, Object> metadata) {}
