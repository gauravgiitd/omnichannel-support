package com.omnichannel.support.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.repo.AuditLogRepository;
import com.omnichannel.support.repo.CustomerContactMappingRepository;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TicketDocumentRepository;
import com.omnichannel.support.repo.TicketMergeMapRepository;
import com.omnichannel.support.repo.TicketRepository;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminCleanupService {

    private final TicketDocumentRepository ticketDocumentRepository;
    private final MessageRepository messageRepository;
    private final TicketMergeMapRepository ticketMergeMapRepository;
    private final CustomerContactMappingRepository customerContactMappingRepository;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final AuditLogRepository auditLogRepository;
    private final TicketRepository ticketRepository;
    private final GoogleDriveStorageService googleDriveStorageService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CleanupResult cleanupAllData() {
        Set<String> driveFileIds = new HashSet<>();
        Set<String> driveFolderIds = new HashSet<>();

        ticketDocumentRepository.findAll().forEach(document -> {
            Map<String, Object> metadata = parseMetadata(document.getMetadataJson());
            addIfPresent(driveFileIds, metadata.get("drive_file_id"));
            addIfPresent(driveFolderIds, metadata.get("drive_folder_id"));
        });

        int documentCount = ticketDocumentRepository.findAll().size();
        int messageCount = messageRepository.findAll().size();
        int mergeCount = ticketMergeMapRepository.findAll().size();
        int identityCount = customerIdentityLinkRepository.findAll().size();
        int contactMappingCount = customerContactMappingRepository.findAll().size();
        int auditCount = auditLogRepository.findAll().size();
        int ticketCount = ticketRepository.findAll().size();

        int deletedDriveFiles = 0;
        int deletedDriveFolders = 0;

        if (googleDriveStorageService.isConfigured()) {
            for (String driveFileId : driveFileIds) {
                try {
                    googleDriveStorageService.deleteFile(driveFileId);
                    deletedDriveFiles++;
                } catch (Exception ignored) {
                }
            }
            for (String driveFolderId : driveFolderIds) {
                try {
                    googleDriveStorageService.deleteFile(driveFolderId);
                    deletedDriveFolders++;
                } catch (Exception ignored) {
                }
            }
        }

        auditLogRepository.deleteAllInBatch();
        messageRepository.deleteAllInBatch();
        ticketDocumentRepository.deleteAllInBatch();
        ticketMergeMapRepository.deleteAllInBatch();
        customerContactMappingRepository.deleteAllInBatch();
        customerIdentityLinkRepository.deleteAllInBatch();
        ticketRepository.deleteAllInBatch();

        return new CleanupResult(
                ticketCount,
                documentCount,
                messageCount,
                identityCount,
                contactMappingCount,
                mergeCount,
                auditCount,
                deletedDriveFiles,
                deletedDriveFolders);
    }

    public AdminSummary summary() {
        return new AdminSummary(
                ticketRepository.count(),
                ticketDocumentRepository.count(),
                messageRepository.count(),
                customerContactMappingRepository.count(),
                customerIdentityLinkRepository.count(),
                auditLogRepository.count(),
                ticketMergeMapRepository.count());
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static void addIfPresent(Set<String> target, Object value) {
        if (value != null && !value.toString().isBlank()) {
            target.add(value.toString());
        }
    }

    public record AdminSummary(
            long tickets,
            long documents,
            long messages,
            long contactMappings,
            long identityLinks,
            long auditLogs,
            long merges) {}

    public record CleanupResult(
            int deletedTickets,
            int deletedDocuments,
            int deletedMessages,
            int deletedIdentityLinks,
            int deletedContactMappings,
            int deletedMerges,
            int deletedAuditLogs,
            int deletedDriveFiles,
            int deletedDriveFolders) {}
}
