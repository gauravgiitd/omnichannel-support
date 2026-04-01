package com.omnichannel.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.MessageIntentType;
import com.omnichannel.support.domain.MessageJtbdLinkageType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Task;
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
    private final MessageLinkService messageLinkService;

    @Transactional(readOnly = true)
    public List<MessageDto> listTimeline(Task task) {
        if (task.getConversation() == null) {
            return messageRepository.findByTaskOrderByCreatedAtAsc(task).stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
        }
        return listTimeline(task.getConversation());
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listTimeline(Conversation conversation) {
        return messageRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listTimeline(Conversation conversation, CustomerJtbd customerJtbd) {
        if (conversation == null) {
            return List.of();
        }
        if (customerJtbd == null) {
            return listTimeline(conversation);
        }
        return messageRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                .filter(message -> message.getCustomerJtbd() == null
                        || message.getCustomerJtbd().getId().equals(customerJtbd.getId()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public MessageDto appendMessage(
            Task task,
            ChannelType channel,
            SenderType senderType,
            String senderIdentifier,
            String body,
            List<String> attachmentUrls,
            String externalThreadRef,
            Map<String, Object> metadata) {
        return appendMessage(
                task.getConversation(),
                task.getCustomerJtbd(),
                task,
                channel,
                senderType,
                senderIdentifier,
                body,
                attachmentUrls,
                externalThreadRef,
                metadata);
    }

    @Transactional
    public MessageDto appendMessage(
            Conversation conversation,
            CustomerJtbd customerJtbd,
            Task task,
            ChannelType channel,
            SenderType senderType,
            String senderIdentifier,
            String body,
            List<String> attachmentUrls,
            String externalThreadRef,
            Map<String, Object> metadata) {
        MessageIntentType intentType = inferIntentType(senderType, body, customerJtbd, metadata);
        Message message = new Message();
        message.setPublicId(UUID.randomUUID().toString());
        message.setConversation(conversation);
        message.setCustomerJtbd(customerJtbd);
        message.setTask(task);
        message.setChannel(channel);
        message.setSenderType(senderType);
        message.setIntentType(intentType);
        message.setSenderIdentifier(senderIdentifier);
        message.setBody(body);
        message.setAttachmentJson(toJsonArray(attachmentUrls));
        message.setExternalThreadRef(externalThreadRef);
        message.setMetadataJson(toJsonObject(metadata));
        Message saved = messageRepository.save(message);
        if (customerJtbd != null) {
            messageLinkService.linkToJtbd(saved, customerJtbd, MessageJtbdLinkageType.ACTIVE_CONTEXT, 0.9d);
        } else if (task != null && task.getCustomerJtbd() != null) {
            messageLinkService.linkToJtbd(saved, task.getCustomerJtbd(), MessageJtbdLinkageType.INFERRED, 0.7d);
        }
        return toDto(saved);
    }

    @Transactional
    public void enrichLatestMessageWithInboundFiles(
            Task task, List<String> fileUrls, List<String> attachmentIds) {
        if ((fileUrls == null || fileUrls.isEmpty()) && (attachmentIds == null || attachmentIds.isEmpty())) {
            return;
        }
        List<Message> messages =
                task.getConversation() != null
                        ? messageRepository.findByConversationOrderByCreatedAtAsc(task.getConversation())
                        : messageRepository.findByTaskOrderByCreatedAtAsc(task);
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
                message.getTask() != null ? message.getTask().getTaskNumber() : null,
                message.getCustomerJtbd() != null ? message.getCustomerJtbd().getPublicId() : null,
                message.getCustomerJtbd() != null ? message.getCustomerJtbd().getJtbdType().getName() : null,
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

    private static MessageIntentType inferIntentType(
            SenderType senderType, String body, CustomerJtbd customerJtbd, Map<String, Object> metadata) {
        if (senderType == SenderType.SYSTEM) {
            return MessageIntentType.SYSTEM_UPDATE;
        }
        if (metadata != null && metadata.containsKey("attachment_ids")) {
            return MessageIntentType.DOCUMENT_SUBMISSION;
        }
        String normalized = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("status")) {
            return MessageIntentType.STATUS_QUERY;
        }
        if (normalized.contains("i want to")
                || normalized.contains("need a refund")
                || normalized.contains("report a claim")
                || normalized.contains("update my policy")) {
            return customerJtbd == null ? MessageIntentType.REQUEST_CREATION : MessageIntentType.REQUEST_UPDATE;
        }
        if (customerJtbd != null) {
            return MessageIntentType.REQUEST_UPDATE;
        }
        return MessageIntentType.GENERAL_QUERY;
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
