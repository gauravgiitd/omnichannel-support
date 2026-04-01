package com.omnichannel.support.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.omnichannel.support.domain.ChannelType;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentCustomerWorkspaceDto(
        String customerId,
        List<String> emails,
        List<String> phones,
        String conversationId,
        ChannelType primaryChannel,
        String defaultTaskId,
        List<String> domains,
        List<CustomerJtbdDto> jtbds,
        List<TaskDto> tasks,
        List<MessageDto> messages,
        List<DocumentDto> documents,
        List<AgentAssignmentDto> assignments,
        List<HandlingSessionDto> handlingSessions) {}
