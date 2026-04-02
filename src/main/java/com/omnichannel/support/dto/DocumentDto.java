package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentDto(
        String documentId,
        String taskId,
        String customerJtbdId,
        String customerJtbdTypeName,
        List<String> customerJtbdTags,
        String customerId,
        String claimId,
        String policyId,
        String documentType,
        String fileUrl,
        ChannelType sourceChannel,
        Map<String, Object> metadata,
        Instant createdAt) {}
