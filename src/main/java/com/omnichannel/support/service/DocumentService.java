package com.omnichannel.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketDocument;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.repo.TicketDocumentRepository;
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
public class DocumentService {

    private final TicketDocumentRepository ticketDocumentRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<DocumentDto> listByTicket(Ticket ticket) {
        return ticketDocumentRepository.findByTicketOrderByCreatedAtAsc(ticket).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public DocumentDto register(
            Ticket ticket,
            String customerId,
            ChannelType sourceChannel,
            String fileUrl,
            String documentType,
            String claimId,
            String policyId,
            Map<String, Object> metadata) {
        TicketDocument doc = new TicketDocument();
        doc.setPublicId(UUID.randomUUID().toString());
        doc.setTicket(ticket);
        doc.setCustomerId(customerId);
        doc.setClaimId(blankToNull(claimId));
        doc.setPolicyId(blankToNull(policyId));
        doc.setDocumentType(documentType != null && !documentType.isBlank() ? documentType : "other");
        doc.setFileUrl(fileUrl);
        doc.setSourceChannel(sourceChannel);
        doc.setMetadataJson(toJson(metadata));
        TicketDocument saved = ticketDocumentRepository.save(doc);

        auditService.record(
                "DOCUMENT_REGISTERED",
                "TicketDocument",
                saved.getPublicId(),
                "SYSTEM",
                "document-service",
                Map.of(
                        "ticket", ticket.getTicketNumber(),
                        "channel", sourceChannel.name()));

        return toDto(saved);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private DocumentDto toDto(TicketDocument doc) {
        return new DocumentDto(
                doc.getPublicId(),
                doc.getTicket().getTicketNumber(),
                doc.getCustomerId(),
                doc.getClaimId(),
                doc.getPolicyId(),
                doc.getDocumentType(),
                doc.getFileUrl(),
                doc.getSourceChannel(),
                parseObjectMap(doc.getMetadataJson()),
                doc.getCreatedAt());
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return "{}";
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
