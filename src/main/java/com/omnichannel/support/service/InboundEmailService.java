package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketPriority;
import com.omnichannel.support.dto.CreateTicketRequest;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.InboundEmailAttachment;
import com.omnichannel.support.dto.InboundEmailRequest;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.TicketDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TicketRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InboundEmailService {

    private static final Pattern SUBJECT_TICKET = Pattern.compile("(?i)\\b(TKT-[A-Z0-9-]+)\\b");

    private final IdentityResolutionService identityResolutionService;
    private final TicketRepository ticketRepository;
    private final MessageRepository messageRepository;
    private final TicketResolutionService ticketResolutionService;
    private final ConversationService conversationService;
    private final TicketService ticketService;
    private final DocumentService documentService;
    private final AuditService auditService;

    @Transactional
    public InboundEmailResult ingest(InboundEmailRequest request) {
        String customerId = resolveCustomerId(request);
        identityResolutionService.registerLink(customerId, IdentifierType.EMAIL, request.fromAddress());
        registerPolicyClaimHints(customerId, request);

        Optional<Ticket> threaded = Optional.empty();
        if (!Boolean.TRUE.equals(request.forceNewTicket())) {
            threaded = resolveThreadTicket(request);
            if (threaded.isEmpty()) {
                threaded = ticketService.findSingleOpenTicketForCustomer(customerId);
            }
        }

        if (threaded.isPresent()) {
            Ticket canonical = ticketResolutionService.resolveCanonical(threaded.get());
            assertCustomerOwns(customerId, canonical);
            List<String> docIds = registerEmailAttachments(canonical, customerId, request);
            List<String> fileUrls = attachmentFileUrls(request);
            Map<String, Object> metadata = buildEmailMetadata(request);
            if (!docIds.isEmpty()) {
                metadata.put("attachment_ids", docIds);
            }
            MessageDto message =
                    conversationService.appendMessage(
                            canonical,
                            ChannelType.EMAIL,
                            SenderType.CUSTOMER,
                            request.fromAddress(),
                            request.bodyText(),
                            fileUrls,
                            normalizeMessageId(request.messageId()),
                            metadata);
            auditService.record(
                    "INBOUND_EMAIL_APPENDED",
                    "Ticket",
                    canonical.getTicketNumber(),
                    "SYSTEM",
                    "email-adapter",
                    Map.of("message_id", message.messageId()));
            return new InboundEmailResult(canonical.getTicketNumber(), message.messageId(), InboundOutcome.APPENDED);
        }

        CreateTicketRequest create =
                new CreateTicketRequest(
                        customerId,
                        IssueTypeParser.fromEmail(request.subject(), request.bodyText()),
                        blankToNull(request.lobHint()),
                        blankToNull(request.claimIdHint()),
                        blankToNull(request.policyIdHint()),
                        TicketPriority.MEDIUM,
                        ChannelType.EMAIL,
                        request.bodyText(),
                        request.fromAddress(),
                        buildEmailMetadata(request),
                        normalizeMessageId(request.messageId()));
        TicketDto ticketDto = ticketService.createTicket(create);
        Ticket ticket =
                ticketRepository
                        .findByTicketNumber(ticketDto.ticketId())
                        .map(ticketResolutionService::resolveCanonical)
                        .orElseThrow(() -> new ValidationException("ticket not found after create"));

        List<String> docIds = registerEmailAttachments(ticket, customerId, request);
        List<String> fileUrls = attachmentFileUrls(request);
        conversationService.enrichLatestMessageWithInboundFiles(ticket, fileUrls, docIds);

        auditService.record(
                "INBOUND_EMAIL_NEW_TICKET",
                "Ticket",
                ticketDto.ticketId(),
                "SYSTEM",
                "email-adapter",
                Map.of());
        return new InboundEmailResult(ticketDto.ticketId(), null, InboundOutcome.CREATED);
    }

    private void registerPolicyClaimHints(String customerId, InboundEmailRequest request) {
        if (request.policyIdHint() != null && !request.policyIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.POLICY_ID, request.policyIdHint().trim());
        }
        if (request.claimIdHint() != null && !request.claimIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.CLAIM_ID, request.claimIdHint().trim());
        }
    }

    private List<String> registerEmailAttachments(
            Ticket canonical, String customerId, InboundEmailRequest request) {
        if (request.attachments() == null || request.attachments().isEmpty()) {
            return List.of();
        }
        String claimHint = blankToNull(request.claimIdHint());
        String policyHint = blankToNull(request.policyIdHint());
        List<String> ids = new ArrayList<>();
        for (InboundEmailAttachment a : request.attachments()) {
            Map<String, Object> meta = new HashMap<>();
            if (a.fileName() != null) {
                meta.put("file_name", a.fileName());
            }
            if (a.mimeType() != null) {
                meta.put("mime_type", a.mimeType());
            }
            meta.put("source", "email_inbound");
            String docType =
                    a.documentType() != null && !a.documentType().isBlank()
                            ? a.documentType()
                            : "email_attachment";
            DocumentDto d =
                    documentService.register(
                            canonical,
                            customerId,
                            ChannelType.EMAIL,
                            a.fileUrl(),
                            docType,
                            claimHint,
                            policyHint,
                            meta);
            ids.add(d.documentId());
        }
        return ids;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static List<String> attachmentFileUrls(InboundEmailRequest request) {
        if (request.attachments() == null || request.attachments().isEmpty()) {
            return List.of();
        }
        return request.attachments().stream().map(InboundEmailAttachment::fileUrl).toList();
    }

    private static void assertCustomerOwns(String customerId, Ticket ticket) {
        if (!ticket.getCustomerId().equals(customerId)) {
            throw new ValidationException("ticket does not belong to resolved customer");
        }
    }

    private String resolveCustomerId(InboundEmailRequest request) {
        if (request.customerIdHint() != null && !request.customerIdHint().isBlank()) {
            return request.customerIdHint().trim();
        }
        return identityResolutionService
                .resolveCustomerId(IdentifierType.EMAIL, request.fromAddress())
                .orElseGet(() -> provisionCustomerForNewEmail(request.fromAddress()));
    }

    private String provisionCustomerForNewEmail(String fromAddress) {
        String newId = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        identityResolutionService.registerLink(newId, IdentifierType.EMAIL, fromAddress);
        return newId;
    }

    private Optional<Ticket> resolveThreadTicket(InboundEmailRequest request) {
        if (request.inReplyTo() != null && !request.inReplyTo().isBlank()) {
            Optional<Ticket> byReply = findTicketByMessageRef(normalizeMessageId(request.inReplyTo()));
            if (byReply.isPresent()) {
                return byReply;
            }
        }
        if (request.references() != null) {
            for (String reference : request.references()) {
                Optional<Ticket> ticket = findTicketByMessageRef(normalizeMessageId(reference));
                if (ticket.isPresent()) {
                    return ticket;
                }
            }
        }
        if (request.subject() != null) {
            Matcher matcher = SUBJECT_TICKET.matcher(request.subject());
            if (matcher.find()) {
                String ticketNumber = matcher.group(1).toUpperCase(Locale.ROOT);
                return ticketRepository.findByTicketNumber(ticketNumber);
            }
        }
        return Optional.empty();
    }

    private Optional<Ticket> findTicketByMessageRef(String normalizedId) {
        if (normalizedId == null || normalizedId.isBlank()) {
            return Optional.empty();
        }
        return messageRepository
                .findByExternalThreadRef(normalizedId)
                .map(m -> ticketResolutionService.resolveCanonical(m.getTicket()));
    }

    private static Map<String, Object> buildEmailMetadata(InboundEmailRequest request) {
        Map<String, Object> meta = new HashMap<>();
        if (request.messageId() != null) {
            meta.put("email_message_id", normalizeMessageId(request.messageId()));
        }
        if (request.inReplyTo() != null) {
            meta.put("in_reply_to", normalizeMessageId(request.inReplyTo()));
        }
        if (request.subject() != null) {
            meta.put("subject", request.subject());
        }
        return meta;
    }

    private static String normalizeMessageId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public enum InboundOutcome {
        CREATED,
        APPENDED
    }

    public record InboundEmailResult(String ticketNumber, String messageId, InboundOutcome outcome) {}
}
