package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketPriority;
import com.omnichannel.support.dto.CreateTicketRequest;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.InboundWhatsAppRequest;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.TicketDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.TicketRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InboundWhatsAppService {

    private final IdentityResolutionService identityResolutionService;
    private final TicketRepository ticketRepository;
    private final TicketResolutionService ticketResolutionService;
    private final ConversationService conversationService;
    private final TicketService ticketService;
    private final DocumentService documentService;
    private final AuditService auditService;

    @Transactional
    public InboundWhatsAppResult ingest(InboundWhatsAppRequest request) {
        String customerId = resolveCustomerId(request);
        identityResolutionService.registerLink(customerId, IdentifierType.PHONE, request.fromE164Phone());
        registerPolicyClaimHints(request, customerId);

        Optional<Ticket> ticketOpt =
                Boolean.TRUE.equals(request.forceNewTicket())
                        ? Optional.empty()
                        : resolveTargetTicket(request, customerId);

        if (ticketOpt.isPresent()) {
            Ticket ticket = ticketResolutionService.resolveCanonical(ticketOpt.get());
            assertCustomerOwns(customerId, ticket);
            List<DocumentDto> documents = registerWhatsAppDocuments(ticket, customerId, request);
            List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
            List<String> urls = documents.stream().map(DocumentDto::fileUrl).toList();
            Map<String, Object> metadata = buildWaMetadata(request);
            if (!docIds.isEmpty()) {
                metadata.put("attachment_ids", docIds);
            }
            MessageDto message =
                    conversationService.appendMessage(
                            ticket,
                            ChannelType.WHATSAPP,
                            SenderType.CUSTOMER,
                            request.fromE164Phone(),
                            request.bodyText(),
                            urls,
                            request.waMessageId(),
                            metadata);
            auditService.record(
                    "INBOUND_WHATSAPP_APPENDED",
                    "Ticket",
                    ticket.getTicketNumber(),
                    "SYSTEM",
                    "whatsapp-adapter",
                    Map.of("message_id", message.messageId()));
            return new InboundWhatsAppResult(ticket.getTicketNumber(), message.messageId(), InboundOutcome.APPENDED);
        }

        Map<String, Object> metadata = buildWaMetadata(request);
        CreateTicketRequest create =
                new CreateTicketRequest(
                        customerId,
                        "whatsapp_inbound",
                        null,
                        blankToNull(request.claimIdHint()),
                        blankToNull(request.policyIdHint()),
                        TicketPriority.MEDIUM,
                        ChannelType.WHATSAPP,
                        request.bodyText(),
                        request.fromE164Phone(),
                        metadata,
                        request.waMessageId());
        TicketDto ticketDto = ticketService.createTicket(create);
        Ticket ticket =
                ticketRepository
                        .findByTicketNumber(ticketDto.ticketId())
                        .map(ticketResolutionService::resolveCanonical)
                        .orElseThrow(() -> new ValidationException("ticket not found after create"));

        List<DocumentDto> documents = registerWhatsAppDocuments(ticket, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> urls = documents.stream().map(DocumentDto::fileUrl).toList();
        conversationService.enrichLatestMessageWithInboundFiles(ticket, urls, docIds);

        auditService.record(
                "INBOUND_WHATSAPP_NEW_TICKET",
                "Ticket",
                ticketDto.ticketId(),
                "SYSTEM",
                "whatsapp-adapter",
                Map.of());
        return new InboundWhatsAppResult(ticketDto.ticketId(), null, InboundOutcome.CREATED);
    }

    private Optional<Ticket> resolveTargetTicket(InboundWhatsAppRequest request, String customerId) {
        if (request.ticketNumberHint() != null && !request.ticketNumberHint().isBlank()) {
            return ticketRepository
                    .findByTicketNumber(request.ticketNumberHint().trim().toUpperCase())
                    .map(ticketResolutionService::resolveCanonical);
        }
        return ticketService.findSingleOpenTicketForCustomer(customerId);
    }

    private void registerPolicyClaimHints(InboundWhatsAppRequest request, String customerId) {
        if (request.policyIdHint() != null && !request.policyIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.POLICY_ID, request.policyIdHint().trim());
        }
        if (request.claimIdHint() != null && !request.claimIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.CLAIM_ID, request.claimIdHint().trim());
        }
    }

    private List<DocumentDto> registerWhatsAppDocuments(Ticket ticket, String customerId, InboundWhatsAppRequest request) {
        if (request.attachmentUrls() == null || request.attachmentUrls().isEmpty()) {
            return List.of();
        }
        String claimHint = blankToNull(request.claimIdHint());
        String policyHint = blankToNull(request.policyIdHint());
        List<DocumentDto> documents = new ArrayList<>();
        int index = 0;
        for (String url : request.attachmentUrls()) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", "whatsapp_inbound");
            meta.put("attachment_index", index++);
            DriveFileToken token = DriveFileToken.parse(url);
            if (token != null) {
                meta.put("drive_file_id", token.fileId());
                if (token.fileName() != null && !token.fileName().isBlank()) {
                    meta.put("file_name", token.fileName());
                }
                if (token.mimeType() != null && !token.mimeType().isBlank()) {
                    meta.put("mime_type", token.mimeType());
                }
            }
            DocumentDto d =
                    documentService.register(
                            ticket,
                            customerId,
                            ChannelType.WHATSAPP,
                            url,
                            "whatsapp_attachment",
                            claimHint,
                            policyHint,
                            meta);
            documents.add(d);
        }
        return documents;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static void assertCustomerOwns(String customerId, Ticket ticket) {
        if (!ticket.getCustomerId().equals(customerId)) {
            throw new ValidationException("ticket does not belong to resolved customer");
        }
    }

    private String resolveCustomerId(InboundWhatsAppRequest request) {
        if (request.customerIdHint() != null && !request.customerIdHint().isBlank()) {
            return request.customerIdHint().trim();
        }
        return identityResolutionService
                .resolveCustomerId(IdentifierType.PHONE, request.fromE164Phone())
                .orElseGet(() -> provisionCustomerForNewPhone(request.fromE164Phone()));
    }

    private String provisionCustomerForNewPhone(String phone) {
        String newId = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        identityResolutionService.registerLink(newId, IdentifierType.PHONE, phone);
        return newId;
    }

    private static Map<String, Object> buildWaMetadata(InboundWhatsAppRequest request) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("wa_message_id", request.waMessageId());
        return meta;
    }

    private record DriveFileToken(String fileId, String fileName, String mimeType) {
        static DriveFileToken parse(String raw) {
            if (raw == null || !raw.startsWith("drive://")) {
                return null;
            }
            String remainder = raw.substring("drive://".length());
            String fileId = remainder;
            String fileName = null;
            String mimeType = null;
            int queryIndex = remainder.indexOf('?');
            if (queryIndex >= 0) {
                fileId = remainder.substring(0, queryIndex);
                String query = remainder.substring(queryIndex + 1);
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = java.net.URLDecoder.decode(part.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    if ("name".equals(key)) {
                        fileName = value;
                    } else if ("mime".equals(key)) {
                        mimeType = value;
                    }
                }
            }
            return new DriveFileToken(fileId, fileName, mimeType);
        }
    }

    public enum InboundOutcome {
        CREATED,
        APPENDED
    }

    public record InboundWhatsAppResult(String ticketNumber, String messageId, InboundOutcome outcome) {}
}
