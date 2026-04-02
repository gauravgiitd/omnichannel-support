package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.SenderType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MessageDto(
        String messageId,
        String taskId,
        String customerJtbdId,
        String customerJtbdTypeName,
        List<String> customerJtbdTags,
        ChannelType channel,
        SenderType senderType,
        String senderIdentifier,
        String body,
        List<String> attachmentUrls,
        List<String> attachmentIds,
        String externalThreadRef,
        Map<String, Object> metadata,
        Instant createdAt) {}
