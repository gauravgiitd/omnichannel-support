package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InboundEmailRequest(
        @NotBlank @Email @Size(max = 320) String fromAddress,
        @NotBlank @Email @Size(max = 320) String toAddress,
        @Size(max = 1024) String subject,
        @NotBlank @Size(max = 100_000) String bodyText,
        @Size(max = 512) String messageId,
        @Size(max = 512) String inReplyTo,
        List<@Size(max = 512) String> references,
        @Size(max = 64) String customerIdHint,
        @Size(max = 64) String policyIdHint,
        @Size(max = 64) String claimIdHint,
        @Size(max = 64) String lobHint,
        Boolean forceNewTask,
        @Valid List<InboundEmailAttachment> attachments) {}
