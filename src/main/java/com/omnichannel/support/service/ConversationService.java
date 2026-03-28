package com.omnichannel.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.repo.MessageRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<MessageDto> listTimeline(Ticket ticket) {
        return messageRepository.findByTicketOrderByCreatedAtAsc(ticket).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public MessageDto appendMessage(
            Ticket ticket,
            ChannelType channel,
            SenderType senderType,
            String senderIdentifier,
            String body,
            List<String> attachmentUrls,
            String externalThreadRef,
            Map<String, Object> metadata) {
        Message message = new Message();
        message.setPublicId(UUID.randomUUID().toString());
        message.setTicket(ticket);
        message.setChannel(channel);
        message.setSenderType(senderType);
        message.setSenderIdentifier(senderIdentifier);
        message.setBody(body);
        message.setAttachmentJson(toJsonArray(attachmentUrls));
        message.setExternalThreadRef(externalThreadRef);
        message.setMetadataJson(toJsonObject(metadata));
        Message saved = messageRepository.save(message);
        return toDto(saved);
    }

    @Transactional
    public void enrichLatestMessageWithInboundFiles(
            Ticket ticket, List<String> fileUrls, List<String> attachmentIds) {
        if ((fileUrls == null || fileUrls.isEmpty()) && (attachmentIds == null || attachmentIds.isEmpty())) {
            return;
        }
        List<Message> messages = messageRepository.findByTicketOrderByCreatedAtAsc(ticket);
        if (messages.isEmpty()) {
            return;
        }
        Message last = messages.get(messages.size() - 1);
        if (fileUrls != null && !fileUrls.isEmpty()) {
            last.setAttachmentJson(toJsonArray(fileUrls));
        }
        Map<String, Object> meta = parseObjectMap(last.getMetadataJson());
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            meta.put("attachment_ids", attachmentIds);
        }
        last.setMetadataJson(toJsonObject(meta));
        messageRepository.save(last);
    }

    private MessageDto toDto(Message message) {
        java.util.Map<String, Object> meta = parseObjectMap(message.getMetadataJson());
        return new MessageDto(
                message.getPublicId(),
                message.getTicket().getTicketNumber(),
                message.getChannel(),
                message.getSenderType(),
                message.getSenderIdentifier(),
                message.getBody(),
                parseStringList(message.getAttachmentJson()),
                extractAttachmentIds(meta),
                message.getExternalThreadRef(),
                meta,
                message.getCreatedAt());
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractAttachmentIds(java.util.Map<String, Object> meta) {
        Object primary = meta.get("attachment_ids");
        if (primary instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        Object legacy = meta.get("document_ids");
        if (legacy instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private String toJsonArray(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String toJsonObject(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> parseObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }
}
