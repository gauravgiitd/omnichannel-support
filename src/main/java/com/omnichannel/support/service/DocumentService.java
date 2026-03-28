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
    private final GoogleDriveStorageService googleDriveStorageService;

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
        Map<String, Object> resolvedMetadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        moveDriveFileToCustomerFolder(customerId, resolvedMetadata);

        TicketDocument doc = new TicketDocument();
        String publicId = UUID.randomUUID().toString();
        doc.setPublicId(publicId);
        doc.setTicket(ticket);
        doc.setCustomerId(customerId);
        doc.setClaimId(blankToNull(claimId));
        doc.setPolicyId(blankToNull(policyId));
        doc.setDocumentType(documentType != null && !documentType.isBlank() ? documentType : "other");
        doc.setFileUrl(resolveStoredFileUrl(publicId, fileUrl, resolvedMetadata));
        doc.setSourceChannel(sourceChannel);
        doc.setMetadataJson(toJson(resolvedMetadata));
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

    private void moveDriveFileToCustomerFolder(String customerId, Map<String, Object> metadata) {
        Object driveFileId = metadata.get("drive_file_id");
        if (driveFileId == null || driveFileId.toString().isBlank() || !googleDriveStorageService.isConfigured()) {
            return;
        }
        try {
            String folderId = googleDriveStorageService.ensureCustomerFolder(customerId);
            googleDriveStorageService.moveToFolder(driveFileId.toString(), folderId);
            metadata.put("drive_folder_id", folderId);
            metadata.put("drive_customer_folder", customerId);
        } catch (Exception ex) {
            metadata.put("drive_folder_move_error", ex.getMessage() != null ? ex.getMessage() : "unknown");
        }
    }

    @Transactional(readOnly = true)
    public TicketDocument getByPublicId(String publicId) {
        return ticketDocumentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new com.omnichannel.support.error.NotFoundException("document not found"));
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String resolveStoredFileUrl(String publicId, String fileUrl, Map<String, Object> metadata) {
        if (metadata != null && metadata.get("drive_file_id") != null) {
            return "/v1/documents/" + publicId + "/content";
        }
        return fileUrl;
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
