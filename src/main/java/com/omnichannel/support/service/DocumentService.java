package com.omnichannel.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskDocument;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.repo.TaskDocumentRepository;
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

    private final TaskDocumentRepository taskDocumentRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final GoogleDriveStorageService googleDriveStorageService;
    private final DocumentLinkService documentLinkService;

    @Transactional(readOnly = true)
    public List<DocumentDto> listByTask(Task task) {
        if (task.getConversation() != null) {
            return listByConversation(task.getConversation(), task.getCustomerJtbd());
        }
        return taskDocumentRepository.findByTaskOrderByCreatedAtAsc(task).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listByConversation(Conversation conversation, CustomerJtbd customerJtbd) {
        if (conversation == null) {
            return List.of();
        }
        if (customerJtbd == null) {
            return taskDocumentRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
        }
        return taskDocumentRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                .filter(doc -> doc.getCustomerJtbd() == null || doc.getCustomerJtbd().getId().equals(customerJtbd.getId()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listCustomerVisibleByConversation(Conversation conversation, CustomerJtbd customerJtbd) {
        if (conversation == null) {
            return List.of();
        }
        return taskDocumentRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                .filter(doc -> !isInternalOnly(parseObjectMap(doc.getMetadataJson())))
                .filter(doc -> customerJtbd == null
                        || doc.getCustomerJtbd() == null
                        || doc.getCustomerJtbd().getId().equals(customerJtbd.getId()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listInternalByTask(Task task) {
        return taskDocumentRepository.findByTaskOrderByCreatedAtAsc(task).stream()
                .filter(doc -> isInternalOnly(parseObjectMap(doc.getMetadataJson())))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public DocumentDto register(
            Task task,
            String customerId,
            ChannelType sourceChannel,
            String fileUrl,
            String documentType,
            String claimId,
            String policyId,
            Map<String, Object> metadata) {
        return register(task.getConversation(), task.getCustomerJtbd(), task, customerId, sourceChannel, fileUrl, documentType, claimId, policyId, metadata);
    }

    @Transactional
    public DocumentDto register(
            Conversation conversation,
            CustomerJtbd customerJtbd,
            Task task,
            String customerId,
            ChannelType sourceChannel,
            String fileUrl,
            String documentType,
            String claimId,
            String policyId,
            Map<String, Object> metadata) {
        Map<String, Object> resolvedMetadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        moveDriveFileToCustomerFolder(customerId, resolvedMetadata);

        TaskDocument doc = new TaskDocument();
        String publicId = UUID.randomUUID().toString();
        doc.setPublicId(publicId);
        doc.setConversation(conversation);
        doc.setCustomerJtbd(customerJtbd);
        doc.setTask(task);
        doc.setCustomerId(customerId);
        doc.setClaimId(blankToNull(claimId));
        doc.setPolicyId(blankToNull(policyId));
        doc.setDocumentType(documentType != null && !documentType.isBlank() ? documentType : "other");
        doc.setFileUrl(resolveStoredFileUrl(publicId, fileUrl, resolvedMetadata));
        doc.setSourceChannel(sourceChannel);
        doc.setMetadataJson(toJson(resolvedMetadata));
        TaskDocument saved = taskDocumentRepository.save(doc);
        documentLinkService.link(saved, conversation, null, customerJtbd, task);

        auditService.record(
                "DOCUMENT_REGISTERED",
                "TaskDocument",
                saved.getPublicId(),
                "SYSTEM",
                "document-service",
                Map.of(
                        "task", task != null ? task.getTaskNumber() : "",
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
    public TaskDocument getByPublicId(String publicId) {
        return taskDocumentRepository.findByPublicId(publicId)
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

    private DocumentDto toDto(TaskDocument doc) {
        List<String> jtbdTags = new java.util.ArrayList<>();
        if (doc.getCustomerJtbd() != null && doc.getCustomerJtbd().getJtbdType() != null) {
            jtbdTags.add(doc.getCustomerJtbd().getJtbdType().getName());
        }
        return new DocumentDto(
                doc.getPublicId(),
                doc.getTask() != null ? doc.getTask().getTaskNumber() : null,
                doc.getCustomerJtbd() != null ? doc.getCustomerJtbd().getPublicId() : null,
                doc.getCustomerJtbd() != null ? doc.getCustomerJtbd().getJtbdType().getName() : null,
                jtbdTags.stream().distinct().toList(),
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

    private static boolean isInternalOnly(Map<String, Object> metadata) {
        Object audience = metadata.get(ConversationService.AUDIENCE_KEY);
        return audience != null && ConversationService.AUDIENCE_INTERNAL.equalsIgnoreCase(audience.toString());
    }
}
