package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.PendingSelectionType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketPriority;
import com.omnichannel.support.dto.CreateTicketRequest;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.InboundWhatsAppRequest;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.TicketDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.MessageRepository;
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

    private static final java.util.regex.Pattern TICKET_NUMBER =
            java.util.regex.Pattern.compile("(?i)\\b(TKT-[A-Z0-9-]+)\\b");
    private static final int MAX_SELECTION_OPTIONS = 5;

    private final IdentityResolutionService identityResolutionService;
    private final CustomerContactMappingService customerContactMappingService;
    private final MessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final TicketResolutionService ticketResolutionService;
    private final ConversationService conversationService;
    private final TicketService ticketService;
    private final DocumentService documentService;
    private final JtbdService jtbdService;
    private final CustomerConversationContextService customerConversationContextService;
    private final CustomerChannelNotificationService customerChannelNotificationService;
    private final AuditService auditService;

    @Transactional
    public InboundWhatsAppResult ingest(InboundWhatsAppRequest request) {
        String customerId = resolveCustomerId(request);
        identityResolutionService.registerLink(customerId, IdentifierType.PHONE, request.fromE164Phone());
        registerPolicyClaimHints(request, customerId);
        List<Ticket> openTickets = ticketService.findOpenTicketsForCustomer(customerId).stream()
                .map(ticketResolutionService::resolveCanonical)
                .distinct()
                .toList();
        List<CustomerJtbd> activeJtbds = jtbdService.activeJtbdsForCustomer(customerId);
        boolean directReply = request.replyToWaMessageId() != null && !request.replyToWaMessageId().isBlank();

        if (Boolean.TRUE.equals(request.forceNewTicket())) {
            return createNewTicket(request, customerId, null);
        }

        Optional<Ticket> explicitTicket = resolveTargetTicket(request, customerId);
        if (explicitTicket.isPresent()) {
            Ticket ticket = ticketResolutionService.resolveCanonical(explicitTicket.get());
            assertCustomerOwns(customerId, ticket);
            customerConversationContextService.setActiveTicket(customerId, ChannelType.WHATSAPP, ticket.getTicketNumber());
            return appendToTicket(ticket, customerId, request);
        }

        Optional<CustomerConversationContextService.SelectionMatch> selectionMatch =
                customerConversationContextService.matchPendingSelection(customerId, ChannelType.WHATSAPP, request.bodyText());
        if (selectionMatch.isPresent()) {
            customerConversationContextService.clearPendingSelection(customerId, ChannelType.WHATSAPP);
            if (selectionMatch.get().type() == PendingSelectionType.TICKET) {
                Ticket ticket = ticketService.loadCanonicalTicket(selectionMatch.get().option().reference());
                assertCustomerOwns(customerId, ticket);
                customerConversationContextService.setActiveTicket(customerId, ChannelType.WHATSAPP, ticket.getTicketNumber());
                return appendToTicket(ticket, customerId, request);
            }
            CustomerJtbd jtbd = jtbdService.loadCustomerJtbd(selectionMatch.get().option().reference());
            if (!jtbd.getCustomerId().equals(customerId)) {
                throw new ValidationException("JTBD does not belong to resolved customer");
            }
            return createNewTicket(request, customerId, jtbd);
        }

        Optional<CustomerConversationContextService.PendingSelection> pendingSelection =
                customerConversationContextService.pendingSelection(customerId, ChannelType.WHATSAPP);
        if (pendingSelection.isPresent()) {
            sendSelectionPrompt(
                    request.fromE164Phone(),
                    buildPromptBody(pendingSelection.get().type(), pendingSelection.get().options()));
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        if (isSwitchToTicketRequest(request.bodyText()) && openTickets.size() > 1) {
            List<CustomerConversationContextService.SelectionOption> options = buildTicketSelectionOptions(openTickets);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.WHATSAPP, PendingSelectionType.TICKET, options);
            sendSelectionPrompt(request.fromE164Phone(), buildPromptBody(PendingSelectionType.TICKET, options));
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        if (isSwitchToJtbdRequest(request.bodyText()) && !activeJtbds.isEmpty()) {
            if (activeJtbds.size() == 1) {
                return createNewTicket(request, customerId, activeJtbds.get(0));
            }
            List<CustomerConversationContextService.SelectionOption> options = buildJtbdSelectionOptions(activeJtbds);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.WHATSAPP, PendingSelectionType.JTBD, options);
            sendSelectionPrompt(request.fromE164Phone(), buildPromptBody(PendingSelectionType.JTBD, options));
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        if (!directReply && openTickets.size() > 1) {
            List<CustomerConversationContextService.SelectionOption> options = buildTicketSelectionOptions(openTickets);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.WHATSAPP, PendingSelectionType.TICKET, options);
            sendSelectionPrompt(request.fromE164Phone(), buildPromptBody(PendingSelectionType.TICKET, options));
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        Optional<Ticket> activeTicket = activeContextTicket(customerId);
        if (activeTicket.isPresent()) {
            return appendToTicket(activeTicket.get(), customerId, request);
        }

        if (!openTickets.isEmpty()) {
            if (openTickets.size() == 1) {
                Ticket ticket = openTickets.get(0);
                customerConversationContextService.setActiveTicket(customerId, ChannelType.WHATSAPP, ticket.getTicketNumber());
                return appendToTicket(ticket, customerId, request);
            }
            List<CustomerConversationContextService.SelectionOption> options = buildTicketSelectionOptions(openTickets);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.WHATSAPP, PendingSelectionType.TICKET, options);
            sendSelectionPrompt(request.fromE164Phone(), buildPromptBody(PendingSelectionType.TICKET, options));
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        if (!activeJtbds.isEmpty()) {
            if (activeJtbds.size() == 1) {
                return createNewTicket(request, customerId, activeJtbds.get(0));
            }
            List<CustomerConversationContextService.SelectionOption> options = buildJtbdSelectionOptions(activeJtbds);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.WHATSAPP, PendingSelectionType.JTBD, options);
            sendSelectionPrompt(request.fromE164Phone(), buildPromptBody(PendingSelectionType.JTBD, options));
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        return createNewTicket(request, customerId, null);
    }

    private Optional<Ticket> activeContextTicket(String customerId) {
        return customerConversationContextService.activeTicketNumber(customerId, ChannelType.WHATSAPP)
                .flatMap(ticketRepository::findByTicketNumber)
                .map(ticketResolutionService::resolveCanonical)
                .filter(ticket -> ticket.getCustomerId().equals(customerId))
                .filter(ticket -> ticketService.findOpenTicketsForCustomer(customerId).stream()
                        .anyMatch(open -> open.getTicketNumber().equals(ticket.getTicketNumber())));
    }

    private InboundWhatsAppResult appendToTicket(Ticket ticket, String customerId, InboundWhatsAppRequest request) {
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
        customerConversationContextService.setActiveTicket(customerId, ChannelType.WHATSAPP, ticket.getTicketNumber());
        auditService.record(
                "INBOUND_WHATSAPP_APPENDED",
                "Ticket",
                ticket.getTicketNumber(),
                "SYSTEM",
                "whatsapp-adapter",
                Map.of("message_id", message.messageId()));
        return new InboundWhatsAppResult(ticket.getTicketNumber(), message.messageId(), InboundOutcome.APPENDED);
    }

    private InboundWhatsAppResult createNewTicket(
            InboundWhatsAppRequest request, String customerId, CustomerJtbd customerJtbd) {
        Map<String, Object> metadata = buildWaMetadata(request);
        if (customerJtbd != null) {
            metadata.put("customer_jtbd_id", customerJtbd.getPublicId());
            metadata.put("jtbd_type", customerJtbd.getJtbdType().getName());
        }
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
        TicketDto ticketDto = ticketService.createTicket(create, customerJtbd);
        Ticket ticket =
                ticketRepository
                        .findByTicketNumber(ticketDto.ticketId())
                        .map(ticketResolutionService::resolveCanonical)
                        .orElseThrow(() -> new ValidationException("ticket not found after create"));

        List<DocumentDto> documents = registerWhatsAppDocuments(ticket, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> urls = documents.stream().map(DocumentDto::fileUrl).toList();
        conversationService.enrichLatestMessageWithInboundFiles(ticket, urls, docIds);
        customerConversationContextService.setActiveTicket(customerId, ChannelType.WHATSAPP, ticket.getTicketNumber());

        auditService.record(
                "INBOUND_WHATSAPP_NEW_TICKET",
                "Ticket",
                ticketDto.ticketId(),
                "SYSTEM",
                "whatsapp-adapter",
                customerJtbd != null ? Map.of("customer_jtbd_id", customerJtbd.getPublicId()) : Map.of());
        return new InboundWhatsAppResult(ticketDto.ticketId(), null, InboundOutcome.CREATED);
    }

    private Optional<Ticket> resolveTargetTicket(InboundWhatsAppRequest request, String customerId) {
        if (request.replyToWaMessageId() != null && !request.replyToWaMessageId().isBlank()) {
            Optional<Ticket> fromReplyContext = messageRepository
                    .findByExternalThreadRef(request.replyToWaMessageId().trim())
                    .map(com.omnichannel.support.domain.Message::getTicket)
                    .map(ticketResolutionService::resolveCanonical);
            if (fromReplyContext.isPresent()) {
                return fromReplyContext;
            }
        }
        if (request.ticketNumberHint() != null && !request.ticketNumberHint().isBlank()) {
            return ticketRepository
                    .findByTicketNumber(request.ticketNumberHint().trim().toUpperCase())
                    .map(ticketResolutionService::resolveCanonical);
        }
        if (request.bodyText() != null) {
            java.util.regex.Matcher matcher = TICKET_NUMBER.matcher(request.bodyText());
            if (matcher.find()) {
                return ticketRepository
                        .findByTicketNumber(matcher.group(1).toUpperCase())
                        .map(ticketResolutionService::resolveCanonical);
            }
        }
        return Optional.empty();
    }

    private void sendSelectionPrompt(String phone, String body) {
        customerChannelNotificationService.send(ChannelType.WHATSAPP, phone, null, body);
    }

    private static boolean isSwitchToTicketRequest(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("SWITCH TICKET")
                || normalized.contains("CHANGE TICKET")
                || normalized.contains("ANOTHER TICKET")
                || normalized.contains("DIFFERENT TICKET");
    }

    private static boolean isSwitchToJtbdRequest(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("SWITCH JOB")
                || normalized.contains("CHANGE JOB")
                || normalized.contains("ANOTHER JOB")
                || normalized.contains("DIFFERENT JOB")
                || normalized.contains("SWITCH JTBD")
                || normalized.contains("CHANGE JTBD");
    }

    private static List<CustomerConversationContextService.SelectionOption> buildTicketSelectionOptions(List<Ticket> openTickets) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(openTickets.size(), MAX_SELECTION_OPTIONS); i++) {
            Ticket ticket = openTickets.get(i);
            options.add(new CustomerConversationContextService.SelectionOption(
                    i + 1,
                    ticket.getTicketNumber(),
                    ticket.getTicketNumber() + " - " + ticket.getIssueType() + " (" + ticket.getStatus() + ")"));
        }
        return options;
    }

    private static List<CustomerConversationContextService.SelectionOption> buildJtbdSelectionOptions(List<CustomerJtbd> activeJtbds) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(activeJtbds.size(), MAX_SELECTION_OPTIONS); i++) {
            CustomerJtbd jtbd = activeJtbds.get(i);
            options.add(new CustomerConversationContextService.SelectionOption(
                    i + 1,
                    jtbd.getPublicId(),
                    jtbd.getJtbdType().getName() + " - " + jtbd.getCurrentStage().getStageName()));
        }
        return options;
    }

    private static String buildPromptBody(
            PendingSelectionType type, List<CustomerConversationContextService.SelectionOption> options) {
        String topic = type == PendingSelectionType.TICKET ? "ticket" : "job";
        StringBuilder builder = new StringBuilder("We found multiple ")
                .append(topic)
                .append(" options for your account. Reply with the number or reference for the one you mean:\n");
        for (CustomerConversationContextService.SelectionOption option : options) {
            builder.append(option.optionNumber())
                    .append(". ")
                    .append(option.label())
                    .append(" [")
                    .append(option.reference())
                    .append("]\n");
        }
        builder.append("We will keep the conversation on that selection until you ask about another one.");
        return builder.toString();
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
        Optional<String> direct = identityResolutionService.resolveCustomerId(IdentifierType.PHONE, request.fromE164Phone());
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> mappedEmail = customerContactMappingService.counterpartForPhone(request.fromE164Phone());
        if (mappedEmail.isPresent()) {
            Optional<String> mappedCustomer = identityResolutionService.resolveCustomerId(IdentifierType.EMAIL, mappedEmail.get());
            if (mappedCustomer.isPresent()) {
                return mappedCustomer.get();
            }
        }
        return provisionCustomerForNewPhone(request.fromE164Phone());
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
        APPENDED,
        PROMPTED
    }

    public record InboundWhatsAppResult(String ticketNumber, String messageId, InboundOutcome outcome) {}
}
