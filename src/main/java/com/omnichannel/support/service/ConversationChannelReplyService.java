package com.omnichannel.support.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.config.SupportPlatformProperties;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.MessageRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationChannelReplyService {

    private final SupportPlatformProperties properties;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final MessageRepository messageRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MetaWhatsAppCloudApiClient metaWhatsAppCloudApiClient;
    private final GoogleDriveStorageService googleDriveStorageService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public OutboundDeliveryResult deliverAgentReply(Conversation conversation, String customerId, String agentEmail, String body) {
        CustomerChannelContext channelContext = resolveChannelContext(conversation, customerId);
        return switch (channelContext.channel()) {
            case EMAIL -> sendEmailReply(conversation, customerId, channelContext, agentEmail, body);
            case WHATSAPP -> sendWhatsAppReply(conversation, customerId, channelContext, agentEmail, body);
            case UI -> new OutboundDeliveryResult(ChannelType.UI, null, null, Map.of("delivery", "app_only"));
        };
    }

    public OutboundDeliveryResult deliverAgentDocument(
            Conversation conversation, String customerId, String agentEmail, DocumentDto document, String messageBody) {
        CustomerChannelContext channelContext = resolveChannelContext(conversation, customerId);
        return switch (channelContext.channel()) {
            case EMAIL -> sendEmailAttachment(conversation, customerId, channelContext, agentEmail, document, messageBody);
            case WHATSAPP -> sendWhatsAppAttachment(conversation, customerId, channelContext, agentEmail, document, messageBody);
            case UI -> new OutboundDeliveryResult(ChannelType.UI, null, null, Map.of("delivery", "app_only_attachment"));
        };
    }

    private OutboundDeliveryResult sendEmailReply(
            Conversation conversation, String customerId, CustomerChannelContext channelContext, String agentEmail, String body) {
        String recipient = channelContext.recipient() != null
                ? channelContext.recipient()
                : findIdentifier(customerId, IdentifierType.EMAIL)
                        .orElseThrow(() -> new ValidationException("customer email not found for conversation"));
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new ValidationException("mail sender not configured");
        }
        String subject = replySubject(channelContext.subject());
        String messageId = buildOutboundMessageId(conversation.getPublicId(), properties.outboundEmail().fromAddress());
        try {
            jakarta.mail.internet.MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(properties.outboundEmail().fromAddress(), properties.outboundEmail().fromDisplayName());
            helper.setReplyTo(properties.outboundEmail().fromAddress());
            helper.setSubject(subject);
            helper.setText(body, false);
            mimeMessage.setHeader("Message-ID", messageId);
            if (channelContext.threadRef() != null) {
                mimeMessage.setHeader("In-Reply-To", wrapAngles(stripAngles(channelContext.threadRef())));
            }
            if (!channelContext.references().isEmpty()) {
                mimeMessage.setHeader("References", String.join(" ", channelContext.references().stream()
                        .map(ConversationChannelReplyService::stripAngles)
                        .map(ConversationChannelReplyService::wrapAngles)
                        .toList()));
            }
            mailSender.send(mimeMessage);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("delivery", "email");
            metadata.put("recipient", recipient);
            metadata.put("email_subject", subject);
            metadata.put("agent_email", agentEmail);
            return new OutboundDeliveryResult(ChannelType.EMAIL, recipient, stripAngles(messageId), metadata);
        } catch (Exception ex) {
            auditService.record(
                    "AGENT_REPLY_EMAIL_FAILED",
                    "Conversation",
                    conversation.getPublicId(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex)));
            throw new ValidationException("failed to send email reply to customer");
        }
    }

    private OutboundDeliveryResult sendWhatsAppReply(
            Conversation conversation, String customerId, CustomerChannelContext channelContext, String agentEmail, String body) {
        String recipient = channelContext.recipient() != null
                ? channelContext.recipient()
                : findIdentifier(customerId, IdentifierType.PHONE)
                        .orElseThrow(() -> new ValidationException("customer phone not found for conversation"));
        if (!metaWhatsAppCloudApiClient.canSendMessages()) {
            throw new ValidationException("WhatsApp Cloud API outbound messaging is not configured");
        }
        try {
            String messageId = metaWhatsAppCloudApiClient.sendTextMessage(recipient, body);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("delivery", "whatsapp");
            metadata.put("recipient", recipient);
            metadata.put("agent_email", agentEmail);
            return new OutboundDeliveryResult(ChannelType.WHATSAPP, recipient, messageId, metadata);
        } catch (Exception ex) {
            auditService.record(
                    "AGENT_REPLY_WHATSAPP_FAILED",
                    "Conversation",
                    conversation.getPublicId(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex)));
            throw new ValidationException("failed to send WhatsApp reply to customer");
        }
    }

    private OutboundDeliveryResult sendEmailAttachment(
            Conversation conversation,
            String customerId,
            CustomerChannelContext channelContext,
            String agentEmail,
            DocumentDto document,
            String messageBody) {
        String recipient = channelContext.recipient() != null
                ? channelContext.recipient()
                : findIdentifier(customerId, IdentifierType.EMAIL)
                        .orElseThrow(() -> new ValidationException("customer email not found for conversation"));
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new ValidationException("mail sender not configured");
        }
        byte[] bytes = loadDriveBytes(document);
        String fileName = fileName(document);
        String mimeType = mimeType(document);
        String subject = replySubject(channelContext.subject());
        String body = hasText(messageBody) ? messageBody : "A supporting document has been added to your conversation.";
        String messageId = buildOutboundMessageId(conversation.getPublicId(), properties.outboundEmail().fromAddress());
        try {
            jakarta.mail.internet.MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(properties.outboundEmail().fromAddress(), properties.outboundEmail().fromDisplayName());
            helper.setReplyTo(properties.outboundEmail().fromAddress());
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(fileName, new ByteArrayResource(bytes), mimeType);
            mimeMessage.setHeader("Message-ID", messageId);
            if (channelContext.threadRef() != null) {
                mimeMessage.setHeader("In-Reply-To", wrapAngles(stripAngles(channelContext.threadRef())));
            }
            if (!channelContext.references().isEmpty()) {
                mimeMessage.setHeader("References", String.join(" ", channelContext.references().stream()
                        .map(ConversationChannelReplyService::stripAngles)
                        .map(ConversationChannelReplyService::wrapAngles)
                        .toList()));
            }
            mailSender.send(mimeMessage);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("delivery", "email_attachment");
            metadata.put("recipient", recipient);
            metadata.put("email_subject", subject);
            metadata.put("agent_email", agentEmail);
            metadata.put("document_id", document.documentId());
            return new OutboundDeliveryResult(ChannelType.EMAIL, recipient, stripAngles(messageId), metadata);
        } catch (Exception ex) {
            auditService.record(
                    "AGENT_ATTACHMENT_EMAIL_FAILED",
                    "Conversation",
                    conversation.getPublicId(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex), "document_id", document.documentId()));
            throw new ValidationException("failed to send email attachment to customer");
        }
    }

    private OutboundDeliveryResult sendWhatsAppAttachment(
            Conversation conversation,
            String customerId,
            CustomerChannelContext channelContext,
            String agentEmail,
            DocumentDto document,
            String messageBody) {
        String recipient = channelContext.recipient() != null
                ? channelContext.recipient()
                : findIdentifier(customerId, IdentifierType.PHONE)
                        .orElseThrow(() -> new ValidationException("customer phone not found for conversation"));
        if (!metaWhatsAppCloudApiClient.canSendMessages()) {
            throw new ValidationException("WhatsApp Cloud API outbound messaging is not configured");
        }
        try {
            byte[] bytes = loadDriveBytes(document);
            String fileName = fileName(document);
            String mimeType = mimeType(document);
            String mediaId = metaWhatsAppCloudApiClient.uploadMedia(fileName, mimeType, bytes);
            String messageId = metaWhatsAppCloudApiClient.sendDocumentMessage(recipient, mediaId, fileName, messageBody);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("delivery", "whatsapp_attachment");
            metadata.put("recipient", recipient);
            metadata.put("agent_email", agentEmail);
            metadata.put("document_id", document.documentId());
            return new OutboundDeliveryResult(ChannelType.WHATSAPP, recipient, messageId, metadata);
        } catch (Exception ex) {
            auditService.record(
                    "AGENT_ATTACHMENT_WHATSAPP_FAILED",
                    "Conversation",
                    conversation.getPublicId(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex), "document_id", document.documentId()));
            throw new ValidationException("failed to send WhatsApp attachment to customer");
        }
    }

    private CustomerChannelContext resolveChannelContext(Conversation conversation, String customerId) {
        List<Message> timeline = messageRepository.findByConversationOrderByCreatedAtAsc(conversation);
        List<String> emailRefs = new ArrayList<>();
        for (Message message : timeline) {
            if (message.getSenderType() == SenderType.CUSTOMER && message.getChannel() == ChannelType.EMAIL && message.getExternalThreadRef() != null) {
                emailRefs.add(message.getExternalThreadRef());
            }
        }
        for (int index = timeline.size() - 1; index >= 0; index -= 1) {
            Message message = timeline.get(index);
            if (message.getSenderType() != SenderType.CUSTOMER || isInternal(message)) {
                continue;
            }
            return switch (message.getChannel()) {
                case EMAIL -> new CustomerChannelContext(
                        ChannelType.EMAIL,
                        message.getSenderIdentifier(),
                        message.getExternalThreadRef(),
                        subjectFrom(message),
                        emailRefs);
                case WHATSAPP -> new CustomerChannelContext(
                        ChannelType.WHATSAPP,
                        message.getSenderIdentifier(),
                        message.getExternalThreadRef(),
                        null,
                        List.of());
                case UI -> new CustomerChannelContext(ChannelType.UI, null, null, null, List.of());
            };
        }
        ChannelType fallback = conversation.getPrimaryChannel() != null ? conversation.getPrimaryChannel() : ChannelType.UI;
        String fallbackRecipient = switch (fallback) {
            case EMAIL -> findIdentifier(customerId, IdentifierType.EMAIL).orElse(null);
            case WHATSAPP -> findIdentifier(customerId, IdentifierType.PHONE).orElse(null);
            case UI -> null;
        };
        return new CustomerChannelContext(fallback, fallbackRecipient, null, null, emailRefs);
    }

    private Optional<String> findIdentifier(String customerId, IdentifierType type) {
        return customerIdentityLinkRepository
                .findFirstByCustomerIdAndIdentifierTypeOrderByCreatedAtAsc(customerId, type)
                .map(CustomerIdentityLink::getIdentifierValue);
    }

    private boolean isInternal(Message message) {
        Map<String, Object> metadata = parseMetadata(message.getMetadataJson());
        Object audience = metadata.get(ConversationService.AUDIENCE_KEY);
        return audience != null && ConversationService.AUDIENCE_INTERNAL.equalsIgnoreCase(audience.toString());
    }

    private String subjectFrom(Message message) {
        Map<String, Object> metadata = parseMetadata(message.getMetadataJson());
        Object subject = metadata.get("subject");
        return subject == null ? null : subject.toString();
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private byte[] loadDriveBytes(DocumentDto document) {
        String driveFileId = document.metadata() != null ? asString(document.metadata().get("drive_file_id")) : null;
        if (!hasText(driveFileId)) {
            throw new ValidationException("document is missing drive file metadata");
        }
        try {
            return googleDriveStorageService.download(driveFileId);
        } catch (Exception ex) {
            throw new ValidationException("failed to load document from storage");
        }
    }

    private static String fileName(DocumentDto document) {
        String value = document.metadata() != null ? asString(document.metadata().get("file_name")) : null;
        return hasText(value) ? value : "attachment-" + document.documentId();
    }

    private static String mimeType(DocumentDto document) {
        String value = document.metadata() != null ? asString(document.metadata().get("mime_type")) : null;
        return hasText(value) ? value : "application/octet-stream";
    }

    private static String replySubject(String subject) {
        if (!hasText(subject)) {
            return "Support update on your request";
        }
        return subject.regionMatches(true, 0, "Re:", 0, 3) ? subject : "Re: " + subject;
    }

    private static String buildOutboundMessageId(String conversationId, String fromAddress) {
        String domain = "support.local";
        int at = fromAddress != null ? fromAddress.indexOf('@') : -1;
        if (at >= 0 && at < fromAddress.length() - 1) {
            domain = fromAddress.substring(at + 1).trim();
        }
        return "<conversation-" + conversationId.toLowerCase() + "-" + UUID.randomUUID() + "@" + domain + ">";
    }

    private static String stripAngles(String messageId) {
        if (messageId == null) {
            return null;
        }
        if (messageId.startsWith("<") && messageId.endsWith(">")) {
            return messageId.substring(1, messageId.length() - 1);
        }
        return messageId;
    }

    private static String wrapAngles(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return messageId;
        }
        return "<" + stripAngles(messageId) + ">";
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : "unknown";
    }

    public record OutboundDeliveryResult(
            ChannelType channel, String recipient, String externalThreadRef, Map<String, Object> metadata) {}

    private record CustomerChannelContext(
            ChannelType channel,
            String recipient,
            String threadRef,
            String subject,
            List<String> references) {}
}
