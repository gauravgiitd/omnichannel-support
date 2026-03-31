package com.omnichannel.support.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketDocument;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.repo.AuditLogRepository;
import com.omnichannel.support.repo.CustomerContactMappingRepository;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TicketDocumentRepository;
import com.omnichannel.support.repo.TicketMergeMapRepository;
import com.omnichannel.support.repo.TicketRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        customerIdentityLinkRepository.deleteAllInBatch();
        ticketRepository.deleteAllInBatch();

        return new CleanupResult(
                ticketCount,
                documentCount,
                messageCount,
                identityCount,
                0,
                mergeCount,
                auditCount,
                deletedDriveFiles,
                deletedDriveFolders);
    }

    @Transactional
    public CustomerCleanupResult cleanupCustomerData(String customerId) {
        String normalizedCustomerId = customerId == null ? null : customerId.trim();
        List<Ticket> tickets = normalizedCustomerId == null || normalizedCustomerId.isBlank()
                ? List.of()
                : ticketRepository.findByCustomerIdOrderByCreatedAtDesc(normalizedCustomerId);
        List<CustomerIdentityLink> identityLinks = normalizedCustomerId == null || normalizedCustomerId.isBlank()
                ? List.of()
                : customerIdentityLinkRepository.findByCustomerId(normalizedCustomerId);

        Set<String> emails = identityLinks.stream()
                .filter(link -> link.getIdentifierType() == IdentifierType.EMAIL)
                .map(CustomerIdentityLink::getIdentifierValue)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> phones = identityLinks.stream()
                .filter(link -> link.getIdentifierType() == IdentifierType.PHONE)
                .map(CustomerIdentityLink::getIdentifierValue)
                .collect(java.util.stream.Collectors.toSet());

        List<com.omnichannel.support.domain.CustomerContactMapping> contactMappings = customerContactMappingRepository
                .findAll()
                .stream()
                .filter(mapping -> emails.contains(mapping.getEmail()) || phones.contains(mapping.getPhone()))
                .toList();

        if (tickets.isEmpty() && identityLinks.isEmpty()) {
            throw new NotFoundException("no customer data found for " + normalizedCustomerId);
        }

        List<TicketDocument> documents = tickets.isEmpty() ? List.of() : ticketDocumentRepository.findByTicketIn(tickets);
        List<com.omnichannel.support.domain.Message> messages = tickets.isEmpty() ? List.of() : messageRepository.findByTicketIn(tickets);
        List<com.omnichannel.support.domain.TicketMergeMap> merges = tickets.isEmpty()
                ? List.of()
                : ticketMergeMapRepository.findByPrimaryTicketInOrMergedTicketIn(tickets, tickets);

        Set<String> driveFileIds = new HashSet<>();
        Set<String> driveFolderIds = new HashSet<>();
        documents.forEach(document -> {
            Map<String, Object> metadata = parseMetadata(document.getMetadataJson());
            addIfPresent(driveFileIds, metadata.get("drive_file_id"));
            addIfPresent(driveFolderIds, metadata.get("drive_folder_id"));
        });

        Set<String> ticketNumbers = tickets.stream().map(Ticket::getTicketNumber).collect(java.util.stream.Collectors.toSet());
        Set<String> documentIds =
                documents.stream().map(TicketDocument::getPublicId).collect(java.util.stream.Collectors.toSet());
        Set<String> messageIds = messages.stream()
                .map(com.omnichannel.support.domain.Message::getPublicId)
                .collect(java.util.stream.Collectors.toSet());

        List<com.omnichannel.support.domain.AuditLogEntry> auditLogs = auditLogRepository.findAll().stream()
                .filter(entry -> ticketNumbers.contains(entry.getEntityId())
                        || documentIds.contains(entry.getEntityId())
                        || messageIds.contains(entry.getEntityId()))
                .toList();

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

        if (!auditLogs.isEmpty()) {
            auditLogRepository.deleteAllInBatch(auditLogs);
        }
        if (!messages.isEmpty()) {
            messageRepository.deleteAllInBatch(messages);
        }
        if (!documents.isEmpty()) {
            ticketDocumentRepository.deleteAllInBatch(documents);
        }
        if (!merges.isEmpty()) {
            ticketMergeMapRepository.deleteAllInBatch(new ArrayList<>(new java.util.LinkedHashSet<>(merges)));
        }
        if (!identityLinks.isEmpty()) {
            customerIdentityLinkRepository.deleteAllInBatch(identityLinks);
        }
        if (!tickets.isEmpty()) {
            ticketRepository.deleteAllInBatch(tickets);
        }

        return new CustomerCleanupResult(
                normalizedCustomerId,
                tickets.size(),
                documents.size(),
                messages.size(),
                identityLinks.size(),
                0,
                merges.size(),
                auditLogs.size(),
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

    public record CustomerCleanupResult(
            String customerId,
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
