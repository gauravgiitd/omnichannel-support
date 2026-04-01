package com.omnichannel.support.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskDocument;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.repo.AuditLogRepository;
import com.omnichannel.support.repo.ConversationRepository;
import com.omnichannel.support.repo.CustomerContactMappingRepository;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TaskDocumentRepository;
import com.omnichannel.support.repo.TaskMergeMapRepository;
import com.omnichannel.support.repo.TaskRepository;
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

    private final TaskDocumentRepository taskDocumentRepository;
    private final MessageRepository messageRepository;
    private final TaskMergeMapRepository taskMergeMapRepository;
    private final ConversationRepository conversationRepository;
    private final CustomerContactMappingRepository customerContactMappingRepository;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final AuditLogRepository auditLogRepository;
    private final TaskRepository taskRepository;
    private final GoogleDriveStorageService googleDriveStorageService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CleanupResult cleanupAllData() {
        Set<String> driveFileIds = new HashSet<>();
        Set<String> driveFolderIds = new HashSet<>();

        taskDocumentRepository.findAll().forEach(document -> {
            Map<String, Object> metadata = parseMetadata(document.getMetadataJson());
            addIfPresent(driveFileIds, metadata.get("drive_file_id"));
            addIfPresent(driveFolderIds, metadata.get("drive_folder_id"));
        });

        int documentCount = taskDocumentRepository.findAll().size();
        int messageCount = messageRepository.findAll().size();
        int mergeCount = taskMergeMapRepository.findAll().size();
        int identityCount = customerIdentityLinkRepository.findAll().size();
        int auditCount = auditLogRepository.findAll().size();
        int taskCount = taskRepository.findAll().size();

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
        taskDocumentRepository.deleteAllInBatch();
        taskMergeMapRepository.deleteAllInBatch();
        customerIdentityLinkRepository.deleteAllInBatch();
        taskRepository.deleteAllInBatch();
        conversationRepository.deleteAllInBatch();

        return new CleanupResult(
                taskCount,
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
        List<Task> tasks = normalizedCustomerId == null || normalizedCustomerId.isBlank()
                ? List.of()
                : taskRepository.findByCustomerIdOrderByCreatedAtDesc(normalizedCustomerId);
        Conversation conversation = normalizedCustomerId == null || normalizedCustomerId.isBlank()
                ? null
                : conversationRepository.findByCustomerId(normalizedCustomerId).orElse(null);
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

        if (tasks.isEmpty() && identityLinks.isEmpty() && conversation == null) {
            throw new NotFoundException("no customer data found for " + normalizedCustomerId);
        }

        List<TaskDocument> documents = conversation != null
                ? taskDocumentRepository.findByConversationOrderByCreatedAtAsc(conversation)
                : tasks.isEmpty() ? List.of() : taskDocumentRepository.findByTaskIn(tasks);
        List<com.omnichannel.support.domain.Message> messages = conversation != null
                ? messageRepository.findByConversationOrderByCreatedAtAsc(conversation)
                : tasks.isEmpty() ? List.of() : messageRepository.findByTaskIn(tasks);
        List<com.omnichannel.support.domain.TaskMergeMap> merges = tasks.isEmpty()
                ? List.of()
                : taskMergeMapRepository.findByPrimaryTaskInOrMergedTaskIn(tasks, tasks);

        Set<String> driveFileIds = new HashSet<>();
        Set<String> driveFolderIds = new HashSet<>();
        documents.forEach(document -> {
            Map<String, Object> metadata = parseMetadata(document.getMetadataJson());
            addIfPresent(driveFileIds, metadata.get("drive_file_id"));
            addIfPresent(driveFolderIds, metadata.get("drive_folder_id"));
        });

        Set<String> taskNumbers = tasks.stream().map(Task::getTaskNumber).collect(java.util.stream.Collectors.toSet());
        Set<String> documentIds =
                documents.stream().map(TaskDocument::getPublicId).collect(java.util.stream.Collectors.toSet());
        Set<String> messageIds = messages.stream()
                .map(com.omnichannel.support.domain.Message::getPublicId)
                .collect(java.util.stream.Collectors.toSet());

        List<com.omnichannel.support.domain.AuditLogEntry> auditLogs = auditLogRepository.findAll().stream()
                .filter(entry -> taskNumbers.contains(entry.getEntityId())
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
            taskDocumentRepository.deleteAllInBatch(documents);
        }
        if (!merges.isEmpty()) {
            taskMergeMapRepository.deleteAllInBatch(new ArrayList<>(new java.util.LinkedHashSet<>(merges)));
        }
        if (!identityLinks.isEmpty()) {
            customerIdentityLinkRepository.deleteAllInBatch(identityLinks);
        }
        if (!tasks.isEmpty()) {
            taskRepository.deleteAllInBatch(tasks);
        }
        if (conversation != null) {
            conversationRepository.delete(conversation);
        }

        return new CustomerCleanupResult(
                normalizedCustomerId,
                tasks.size(),
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
                taskRepository.count(),
                taskDocumentRepository.count(),
                messageRepository.count(),
                customerContactMappingRepository.count(),
                customerIdentityLinkRepository.count(),
                auditLogRepository.count(),
                taskMergeMapRepository.count());
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
            long tasks,
            long documents,
            long messages,
            long contactMappings,
            long identityLinks,
            long auditLogs,
            long merges) {}

    public record CleanupResult(
            int deletedTasks,
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
            int deletedTasks,
            int deletedDocuments,
            int deletedMessages,
            int deletedIdentityLinks,
            int deletedContactMappings,
            int deletedMerges,
            int deletedAuditLogs,
            int deletedDriveFiles,
            int deletedDriveFolders) {}
}
