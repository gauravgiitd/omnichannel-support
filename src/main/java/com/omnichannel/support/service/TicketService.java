package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketStatus;
import com.omnichannel.support.dto.CreateTicketRequest;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PatchTicketRequest;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.dto.TicketDto;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.repo.TicketMergeMapRepository;
import com.omnichannel.support.repo.TicketRepository;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketService {

    private static final Set<TicketStatus> OPEN_LIKE =
            EnumSet.of(
                    TicketStatus.OPEN,
                    TicketStatus.ASSIGNED,
                    TicketStatus.PENDING_CUSTOMER,
                    TicketStatus.PENDING_INTERNAL,
                    TicketStatus.REOPENED);

    private final TicketRepository ticketRepository;
    private final TicketMergeMapRepository ticketMergeMapRepository;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final ConversationService conversationService;
    private final TicketResolutionService ticketResolutionService;
    private final AuditService auditService;
    private final DocumentService documentService;
    private final RoutingService routingService;

    @Transactional
    public TicketDto createTicket(CreateTicketRequest request) {
        Ticket ticket = new Ticket();
        ticket.setTicketNumber(ticketNumberGenerator.newTicketNumber());
        ticket.setCustomerId(request.customerId());
        ticket.setIssueType(request.issueType().trim());
        ticket.setLob(blankToNull(request.lob()));
        ticket.setClaimId(blankToNull(request.claimId()));
        ticket.setPolicyId(blankToNull(request.policyId()));
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority(request.priority());
        ticket.setSourceChannel(request.sourceChannel());
        ticket.setAssignedQueue(
                routingService.resolveQueue(
                        RoutingContext.builder()
                                .issueType(ticket.getIssueType())
                                .lob(ticket.getLob())
                                .claimId(ticket.getClaimId())
                                .policyId(ticket.getPolicyId())
                                .customerId(ticket.getCustomerId())
                                .build()));
        ticketRepository.save(ticket);

        String sender =
                request.senderIdentifier() != null && !request.senderIdentifier().isBlank()
                        ? request.senderIdentifier()
                        : request.customerId();
        conversationService.appendMessage(
                ticket,
                request.sourceChannel(),
                SenderType.CUSTOMER,
                sender,
                request.initialMessageBody(),
                List.of(),
                request.initialExternalThreadRef(),
                request.initialMessageMetadata() != null
                        ? request.initialMessageMetadata()
                        : java.util.Map.of());

        auditService.record(
                "TICKET_CREATED",
                "Ticket",
                ticket.getTicketNumber(),
                "SYSTEM",
                "ticket-service",
                java.util.Map.of(
                        "channel",
                        request.sourceChannel().name(),
                        "assigned_queue",
                        ticket.getAssignedQueue() != null ? ticket.getAssignedQueue() : ""));

        return toDto(ticket);
    }

    @Transactional
    public TicketDto patchTicket(String ticketNumber, PatchTicketRequest request) {
        Ticket ticket = loadCanonicalTicket(ticketNumber);
        if (request.issueType() != null && !request.issueType().isBlank()) {
            ticket.setIssueType(request.issueType().trim());
        }
        if (request.lob() != null) {
            ticket.setLob(blankToNull(request.lob()));
        }
        if (request.claimId() != null) {
            ticket.setClaimId(blankToNull(request.claimId()));
        }
        if (request.policyId() != null) {
            ticket.setPolicyId(blankToNull(request.policyId()));
        }
        if (request.assignedAgent() != null) {
            ticket.setAssignedAgent(blankToNull(request.assignedAgent()));
        }
        if (request.status() != null) {
            ticket.setStatus(request.status());
        }
        boolean explicitQueue = request.assignedQueue() != null && !request.assignedQueue().isBlank();
        if (explicitQueue) {
            ticket.setAssignedQueue(request.assignedQueue().trim());
        } else if (routingFieldsPresentInPatch(request)) {
            ticket.setAssignedQueue(
                    routingService.resolveQueue(
                            RoutingContext.builder()
                                    .issueType(ticket.getIssueType())
                                    .lob(ticket.getLob())
                                    .claimId(ticket.getClaimId())
                                    .policyId(ticket.getPolicyId())
                                    .customerId(ticket.getCustomerId())
                                    .build()));
        }
        ticketRepository.save(ticket);
        auditService.record(
                "TICKET_UPDATED",
                "Ticket",
                ticket.getTicketNumber(),
                "SYSTEM",
                "ticket-service",
                java.util.Map.of("queue", ticket.getAssignedQueue() != null ? ticket.getAssignedQueue() : ""));
        return toDto(ticket);
    }

    private static boolean routingFieldsPresentInPatch(PatchTicketRequest request) {
        return request.issueType() != null
                || request.lob() != null
                || request.claimId() != null
                || request.policyId() != null;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    @Transactional(readOnly = true)
    public List<TicketDto> listAllTickets() {
        return ticketRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(t -> !ticketMergeMapRepository.existsByMergedTicket(t))
                .map(ticketResolutionService::resolveCanonical)
                .distinct()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TicketDto getTicket(String ticketNumber) {
        Ticket ticket = loadCanonicalTicket(ticketNumber);
        return toDto(ticket);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listMessages(String ticketNumber) {
        Ticket ticket = loadCanonicalTicket(ticketNumber);
        return conversationService.listTimeline(ticket);
    }

    @Transactional
    public MessageDto postMessage(String ticketNumber, PostMessageRequest request) {
        Ticket ticket = loadCanonicalTicket(ticketNumber);
        MessageDto message =
                conversationService.appendMessage(
                        ticket,
                        request.channel(),
                        request.senderType(),
                        request.senderIdentifier(),
                        request.body(),
                        request.attachmentUrls() != null ? request.attachmentUrls() : List.of(),
                        request.externalThreadRef(),
                        request.metadata());

        auditService.record(
                "MESSAGE_APPENDED",
                "Message",
                message.messageId(),
                request.senderType().name(),
                request.senderIdentifier(),
                java.util.Map.of("ticket", ticket.getTicketNumber(), "channel", request.channel().name()));

        return message;
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listDocuments(String ticketNumber) {
        Ticket ticket = loadCanonicalTicket(ticketNumber);
        return documentService.listByTicket(ticket);
    }

    @Transactional
    public DocumentDto registerDocument(String ticketNumber, RegisterDocumentRequest request) {
        Ticket ticket = loadCanonicalTicket(ticketNumber);
        Map<String, Object> meta = new HashMap<>();
        if (request.metadata() != null) {
            meta.putAll(request.metadata());
        }
        DocumentDto doc =
                documentService.register(
                        ticket,
                        ticket.getCustomerId(),
                        request.channel(),
                        request.fileUrl(),
                        request.documentType(),
                        request.claimId(),
                        request.policyId(),
                        meta);

        Map<String, Object> messageMeta = new HashMap<>();
        messageMeta.put("attachment_ids", List.of(doc.documentId()));
        conversationService.appendMessage(
                ticket,
                request.channel(),
                request.senderType(),
                request.senderIdentifier(),
                documentMessageBody(request),
                List.of(request.fileUrl()),
                null,
                messageMeta);

        auditService.record(
                "DOCUMENT_MESSAGE_APPENDED",
                "Ticket",
                ticket.getTicketNumber(),
                request.senderType().name(),
                request.senderIdentifier(),
                Map.of("document_id", doc.documentId()));

        return doc;
    }

    private static String documentMessageBody(RegisterDocumentRequest request) {
        if (request.messageBody() != null && !request.messageBody().isBlank()) {
            return request.messageBody().trim();
        }
        return "Document uploaded";
    }

    @Transactional(readOnly = true)
    public Optional<Ticket> findSingleOpenTicketForCustomer(String customerId) {
        List<Ticket> open = findOpenTicketsForCustomer(customerId);
        if (open.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(ticketResolutionService.resolveCanonical(open.get(0)));
    }

    @Transactional(readOnly = true)
    public List<TicketDto> listTicketsForCustomer(String customerId) {
        return ticketRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .filter(t -> !ticketMergeMapRepository.existsByMergedTicket(t))
                .map(ticketResolutionService::resolveCanonical)
                .distinct()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public Ticket loadCanonicalTicket(String ticketNumber) {
        Ticket ticket =
                ticketRepository
                        .findByTicketNumber(ticketNumber)
                        .orElseThrow(() -> new NotFoundException("ticket not found"));
        return ticketResolutionService.resolveCanonical(ticket);
    }

    private TicketDto toDto(Ticket ticket) {
        return new TicketDto(
                ticket.getTicketNumber(),
                ticket.getCustomerId(),
                ticket.getIssueType(),
                ticket.getLob(),
                ticket.getClaimId(),
                ticket.getPolicyId(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getSourceChannel(),
                ticket.getAssignedQueue(),
                ticket.getAssignedAgent(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt());
    }

    public List<Ticket> findOpenTicketsForCustomer(String customerId) {
        return ticketRepository.findByCustomerIdAndStatusInOrderByCreatedAtDesc(
                customerId, OPEN_LIKE);
    }
}
