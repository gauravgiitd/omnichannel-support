package com.omnichannel.support.service;

import com.omnichannel.support.config.SupportPlatformProperties;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketEmailNotificationService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final SupportPlatformProperties properties;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final ConversationService conversationService;
    private final AuditService auditService;
    private final MetaWhatsAppCloudApiClient metaWhatsAppCloudApiClient;

    public void sendTicketCreatedNotifications(Ticket ticket) {
        if (ticket.getSourceChannel() == ChannelType.WHATSAPP) {
            sendTicketCreatedWhatsApp(ticket);
        }
        sendTicketCreatedEmail(ticket);
    }

    public void sendTicketCreatedEmail(Ticket ticket) {
        Optional<String> emailOpt = findCustomerEmail(ticket.getCustomerId());
        if (emailOpt.isEmpty()) {
            auditService.record(
                    "TICKET_EMAIL_SKIPPED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "ticket-email",
                    Map.of("reason", "customer email not found"));
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            auditService.record(
                    "TICKET_EMAIL_SKIPPED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "ticket-email",
                    Map.of("reason", "mail sender not configured"));
            return;
        }

        String toAddress = emailOpt.get();
        String subject = "[" + ticket.getTicketNumber() + "] Your support ticket is open";
        String ticketUrl = customerTicketUrl(ticket.getTicketNumber());
        String body = """
                Your support ticket is now open.

                Ticket number: %s

                Open your ticket here:
                %s

                You can either open the ticket in the customer app using the link above, or reply directly to this email with more context or attach documents. Either way, we will add everything to the same ticket.
                """.formatted(ticket.getTicketNumber(), ticketUrl);
        String messageId = buildOutboundMessageId(ticket.getTicketNumber(), properties.outboundEmail().fromAddress());

        try {
            jakarta.mail.internet.MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());
            helper.setTo(toAddress);
            helper.setFrom(properties.outboundEmail().fromAddress(), properties.outboundEmail().fromDisplayName());
            helper.setReplyTo(properties.outboundEmail().fromAddress());
            helper.setSubject(subject);
            helper.setText(body, false);
            mimeMessage.setHeader("Message-ID", messageId);

            mailSender.send(mimeMessage);

            conversationService.appendMessage(
                    ticket,
                    ChannelType.EMAIL,
                    SenderType.SYSTEM,
                    properties.outboundEmail().fromAddress(),
                    body,
                    java.util.List.of(),
                    stripAngles(messageId),
                    Map.of(
                            "direction", "outbound_ticket_created_email",
                            "email_subject", subject,
                            "recipient", toAddress,
                            "customer_ticket_url", ticketUrl));

            auditService.record(
                    "TICKET_EMAIL_SENT",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                "ticket-email",
                Map.of("recipient", toAddress, "message_id", stripAngles(messageId)));
        } catch (Exception ex) {
            auditService.record(
                    "TICKET_EMAIL_FAILED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "ticket-email",
                Map.of("recipient", toAddress, "error", ex.getMessage() != null ? ex.getMessage() : "unknown"));
        }
    }

    public void sendTicketCreatedWhatsApp(Ticket ticket) {
        Optional<String> phoneOpt = findCustomerPhone(ticket.getCustomerId());
        if (phoneOpt.isEmpty()) {
            auditService.record(
                    "TICKET_WHATSAPP_SKIPPED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "ticket-whatsapp",
                    Map.of("reason", "customer phone not found"));
            return;
        }
        if (!metaWhatsAppCloudApiClient.canSendMessages()) {
            auditService.record(
                    "TICKET_WHATSAPP_SKIPPED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "ticket-whatsapp",
                    Map.of("reason", "whatsapp outbound not configured"));
            return;
        }

        String recipient = phoneOpt.get();
        String ticketUrl = customerTicketUrl(ticket.getTicketNumber());
        String body = """
                Your support ticket is now open.

                Ticket number: %s

                Open your ticket here:
                %s

                You can continue the conversation here on WhatsApp or open the ticket in the customer app using the link above. Either way, we will add everything to the same ticket.
                """.formatted(ticket.getTicketNumber(), ticketUrl);
        try {
            String messageId = metaWhatsAppCloudApiClient.sendTextMessage(recipient, body);
            conversationService.appendMessage(
                    ticket,
                    ChannelType.WHATSAPP,
                    SenderType.SYSTEM,
                    properties.systemIdentity() != null ? properties.systemIdentity().agentReplyFromAddress() : "support",
                    body,
                    java.util.List.of(),
                    messageId,
                    Map.of(
                            "direction", "outbound_ticket_created_whatsapp",
                            "recipient", recipient,
                            "customer_ticket_url", ticketUrl));

            auditService.record(
                    "TICKET_WHATSAPP_SENT",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "ticket-whatsapp",
                    Map.of("recipient", recipient, "message_id", messageId != null ? messageId : ""));
        } catch (Exception ex) {
            auditService.record(
                    "TICKET_WHATSAPP_FAILED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "ticket-whatsapp",
                    Map.of("recipient", recipient, "error", ex.getMessage() != null ? ex.getMessage() : "unknown"));
        }
    }

    private String customerTicketUrl(String ticketNumber) {
        String baseUrl = properties.app() != null ? properties.app().baseUrl() : null;
        String normalizedBase = (baseUrl == null || baseUrl.isBlank())
                ? "http://localhost:8080"
                : baseUrl.replaceAll("/+$", "");
        return normalizedBase + "/customer?ticket=" + ticketNumber;
    }

    private Optional<String> findCustomerEmail(String customerId) {
        return customerIdentityLinkRepository
                .findFirstByCustomerIdAndIdentifierTypeOrderByCreatedAtAsc(customerId, IdentifierType.EMAIL)
                .map(CustomerIdentityLink::getIdentifierValue);
    }

    private Optional<String> findCustomerPhone(String customerId) {
        return customerIdentityLinkRepository
                .findFirstByCustomerIdAndIdentifierTypeOrderByCreatedAtAsc(customerId, IdentifierType.PHONE)
                .map(CustomerIdentityLink::getIdentifierValue);
    }

    private static String buildOutboundMessageId(String ticketNumber, String fromAddress) {
        String domain = "support.local";
        int at = fromAddress != null ? fromAddress.indexOf('@') : -1;
        if (at >= 0 && at < fromAddress.length() - 1) {
            domain = fromAddress.substring(at + 1).trim();
        }
        return "<ticket-" + ticketNumber.toLowerCase() + "-" + UUID.randomUUID() + "@" + domain + ">";
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
}
