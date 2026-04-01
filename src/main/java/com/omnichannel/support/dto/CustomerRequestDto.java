package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerRequestDto(
        String requestId,
        String customerId,
        String title,
        String stageLabel,
        String statusLabel,
        ChannelType sourceChannel,
        boolean jtbdBacked,
        String jtbdId,
        String jtbdTypeName,
        String jtbdStageName,
        String jtbdStatus,
        long internalTaskCount,
        Instant createdAt,
        Instant updatedAt) {}
