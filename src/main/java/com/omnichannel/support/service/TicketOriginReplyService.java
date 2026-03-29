package com.omnichannel.support.service;

import com.omnichannel.support.config.SupportPlatformProperties;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.MessageRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketOriginReplyService {

    private final SupportPlatformProperties properties;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final MessageRepository messageRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MetaWhatsAppCloudApiClient metaWhatsAppCloudApiClient;
    private final GoogleDriveStorageService googleDriveStorageService;
    private final AuditService auditService;

    public DeliveryDebug debugTicketRouting(Ticket ticket) {
        List<CustomerIdentityLink> identityLinks = customerIdentityLinkRepository.findByCustomerId(ticket.getCustomerId());
        List<Message> timeline = messageRepository.findByTicketOrderByCreatedAtAsc(ticket);

        Optional<String> threadEmail = findThreadRecipient(ticket, ChannelType.EMAIL);
        Optional<String> threadWhatsApp = findThreadRecipient(ticket, ChannelType.WHATSAPP);
        Optional<String> customerEmail = findIdentifier(ticket.getCustomerId(), IdentifierType.EMAIL);
        Optional<String> customerPhone = findIdentifier(ticket.getCustomerId(), IdentifierType.PHONE);
        Optional<String> resolvedRecipient = resolveOriginRecipient(ticket, ticket.getSourceChannel());

        return new DeliveryDebug(
                ticket.getTicketNumber(),
                ticket.getCustomerId(),
                ticket.getSourceChannel(),
                identityLinks.stream()
                        .map(link -> new IdentityLinkDebug(
                                link.getIdentifierType().name(),
                                link.getIdentifierValue(),
                                link.getCreatedAt()))
                        .collect(Collectors.toList()),
                timeline.stream()
                        .map(message -> new MessageRouteDebug(
                                message.getPublicId(),
                                message.getChannel().name(),
                                message.getSenderType().name(),
                                message.getSenderIdentifier(),
                                message.getCreatedAt()))
                        .collect(Collectors.toList()),
                threadEmail.orElse(null),
                threadWhatsApp.orElse(null),
                customerEmail.orElse(null),
                customerPhone.orElse(null),
                resolvedRecipient.orElse(null));
    }

    public OutboundDeliveryResult deliverAgentReply(Ticket ticket, String agentEmail, String body) {
        return switch (ticket.getSourceChannel()) {
            case EMAIL -> sendEmailReply(ticket, agentEmail, body);
            case WHATSAPP -> sendWhatsAppReply(ticket, agentEmail, body);
            case UI -> new OutboundDeliveryResult(ChannelType.UI, null, null, Map.of("delivery", "app_only"));
        };
    }

    private OutboundDeliveryResult sendEmailReply(Ticket ticket, String agentEmail, String body) {
        String recipient = resolveOriginRecipient(ticket, ChannelType.EMAIL)
                .orElseThrow(() -> new ValidationException("customer email not found for ticket origin"));
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new ValidationException("mail sender not configured");
        }

        String subject = "[" + ticket.getTicketNumber() + "] Support update";
        String ticketUrl = customerTicketUrl(ticket.getTicketNumber());
        String messageBody = """
                Support update for ticket %s

                %s

                You can continue the conversation by replying to this email or by opening your ticket here:
                %s
                """.formatted(ticket.getTicketNumber(), body, ticketUrl);
        String messageId = buildOutboundMessageId(ticket.getTicketNumber(), properties.outboundEmail().fromAddress());
        try {
            jakarta.mail.internet.MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(properties.outboundEmail().fromAddress(), properties.outboundEmail().fromDisplayName());
            helper.setReplyTo(properties.outboundEmail().fromAddress());
            helper.setSubject(subject);
            helper.setText(messageBody, false);
            mimeMessage.setHeader("Message-ID", messageId);
            mailSender.send(mimeMessage);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("delivery", "email");
            metadata.put("recipient", recipient);
            metadata.put("email_subject", subject);
            metadata.put("customer_ticket_url", ticketUrl);
            metadata.put("agent_email", agentEmail);
            return new OutboundDeliveryResult(ChannelType.EMAIL, recipient, stripAngles(messageId), metadata);
        } catch (Exception ex) {
            auditService.record(
                    "AGENT_REPLY_EMAIL_FAILED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex)));
            throw new ValidationException("failed to send email reply to customer");
        }
    }

    private OutboundDeliveryResult sendWhatsAppReply(Ticket ticket, String agentEmail, String body) {
        String recipient = resolveOriginRecipient(ticket, ChannelType.WHATSAPP)
                .orElseThrow(() -> new ValidationException("customer phone not found for ticket origin"));
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
                    "Ticket",
                    ticket.getTicketNumber(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex)));
            throw new ValidationException("failed to send WhatsApp reply to customer");
        }
    }

    public OutboundDeliveryResult deliverAgentDocument(
            Ticket ticket, String agentEmail, DocumentDto document, String messageBody) {
        return switch (ticket.getSourceChannel()) {
            case EMAIL -> sendEmailAttachment(ticket, agentEmail, document, messageBody);
            case WHATSAPP -> sendWhatsAppAttachment(ticket, agentEmail, document, messageBody);
            case UI -> new OutboundDeliveryResult(ChannelType.UI, null, null, Map.of("delivery", "app_only_attachment"));
        };
    }

    private String customerTicketUrl(String ticketNumber) {
        String baseUrl = properties.app() != null ? properties.app().baseUrl() : null;
        String normalizedBase = (baseUrl == null || baseUrl.isBlank())
                ? "http://localhost:8080"
                : baseUrl.replaceAll("/+$", "");
        return normalizedBase + "/customer?ticket=" + ticketNumber;
    }

    private Optional<String> resolveOriginRecipient(Ticket ticket, ChannelType channelType) {
        Optional<String> threadRecipient = findThreadRecipient(ticket, channelType);
        if (threadRecipient.isPresent()) {
            return threadRecipient;
        }
        return switch (channelType) {
            case EMAIL -> findIdentifier(ticket.getCustomerId(), IdentifierType.EMAIL);
            case WHATSAPP -> findIdentifier(ticket.getCustomerId(), IdentifierType.PHONE);
            case UI -> Optional.empty();
        };
    }

    private Optional<String> findThreadRecipient(Ticket ticket, ChannelType channelType) {
        List<Message> timeline = messageRepository.findByTicketOrderByCreatedAtAsc(ticket);
        for (int index = timeline.size() - 1; index >= 0; index -= 1) {
            Message message = timeline.get(index);
            if (message.getSenderType() != SenderType.CUSTOMER) {
                continue;
            }
            if (message.getChannel() != channelType) {
                continue;
            }
            String sender = message.getSenderIdentifier();
            if (sender != null && !sender.isBlank()) {
                return Optional.of(sender);
            }
        }
        return Optional.empty();
    }

    private Optional<String> findIdentifier(String customerId, IdentifierType type) {
        return customerIdentityLinkRepository
                .findFirstByCustomerIdAndIdentifierTypeOrderByCreatedAtAsc(customerId, type)
                .map(CustomerIdentityLink::getIdentifierValue);
    }

    private OutboundDeliveryResult sendEmailAttachment(
            Ticket ticket, String agentEmail, DocumentDto document, String messageBody) {
        String recipient = resolveOriginRecipient(ticket, ChannelType.EMAIL)
                .orElseThrow(() -> new ValidationException("customer email not found for ticket origin"));
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new ValidationException("mail sender not configured");
        }
        byte[] bytes = loadDriveBytes(document);
        String fileName = fileName(document);
        String mimeType = mimeType(document);
        String subject = "[" + ticket.getTicketNumber() + "] Support attachment";
        String body = hasText(messageBody)
                ? messageBody
                : "A supporting document has been added to your support ticket.";
        String ticketUrl = customerTicketUrl(ticket.getTicketNumber());
        String messageId = buildOutboundMessageId(ticket.getTicketNumber(), properties.outboundEmail().fromAddress());
        try {
            jakarta.mail.internet.MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(properties.outboundEmail().fromAddress(), properties.outboundEmail().fromDisplayName());
            helper.setReplyTo(properties.outboundEmail().fromAddress());
            helper.setSubject(subject);
            helper.setText(body + "\n\nOpen your ticket here:\n" + ticketUrl, false);
            helper.addAttachment(fileName, new ByteArrayResource(bytes), mimeType);
            mimeMessage.setHeader("Message-ID", messageId);
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
                    "Ticket",
                    ticket.getTicketNumber(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex), "document_id", document.documentId()));
            throw new ValidationException("failed to send email attachment to customer");
        }
    }

    private OutboundDeliveryResult sendWhatsAppAttachment(
            Ticket ticket, String agentEmail, DocumentDto document, String messageBody) {
        String recipient = resolveOriginRecipient(ticket, ChannelType.WHATSAPP)
                .orElseThrow(() -> new ValidationException("customer phone not found for ticket origin"));
        if (!metaWhatsAppCloudApiClient.canSendMessages()) {
            throw new ValidationException("WhatsApp Cloud API outbound messaging is not configured");
        }
        try {
            byte[] bytes = loadDriveBytes(document);
            String fileName = fileName(document);
            String mimeType = mimeType(document);
            String mediaId = metaWhatsAppCloudApiClient.uploadMedia(fileName, mimeType, bytes);
            String messageId =
                    metaWhatsAppCloudApiClient.sendDocumentMessage(recipient, mediaId, fileName, messageBody);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("delivery", "whatsapp_attachment");
            metadata.put("recipient", recipient);
            metadata.put("agent_email", agentEmail);
            metadata.put("document_id", document.documentId());
            return new OutboundDeliveryResult(ChannelType.WHATSAPP, recipient, messageId, metadata);
        } catch (Exception ex) {
            auditService.record(
                    "AGENT_ATTACHMENT_WHATSAPP_FAILED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "AGENT",
                    agentEmail,
                    Map.of("recipient", recipient, "error", safeMessage(ex), "document_id", document.documentId()));
            throw new ValidationException("failed to send WhatsApp attachment to customer");
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

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String buildOutboundMessageId(String ticketNumber, String fromAddress) {
        String domain = "support.local";
        int at = fromAddress != null ? fromAddress.indexOf('@') : -1;
        if (at >= 0 && at < fromAddress.length() - 1) {
            domain = fromAddress.substring(at + 1).trim();
        }
        return "<agent-" + ticketNumber.toLowerCase() + "-" + UUID.randomUUID() + "@" + domain + ">";
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

    private static String safeMessage(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : "unknown";
    }

    public record OutboundDeliveryResult(
            ChannelType channel, String recipient, String externalThreadRef, Map<String, Object> metadata) {}

    public record DeliveryDebug(
            String ticketId,
            String customerId,
            ChannelType sourceChannel,
            List<IdentityLinkDebug> identityLinks,
            List<MessageRouteDebug> timeline,
            String latestThreadEmailRecipient,
            String latestThreadWhatsAppRecipient,
            String customerEmailFallback,
            String customerPhoneFallback,
            String resolvedOutboundRecipient) {}

    public record IdentityLinkDebug(String identifierType, String identifierValue, java.time.Instant createdAt) {}

    public record MessageRouteDebug(
            String messageId, String channel, String senderType, String senderIdentifier, java.time.Instant createdAt) {}
}
