package com.omnichannel.support.service;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskStatus;
import com.omnichannel.support.dto.AgentAssignmentDto;
import com.omnichannel.support.dto.AgentCustomerWorkspaceDto;
import com.omnichannel.support.dto.CustomerJtbdDto;
import com.omnichannel.support.dto.CustomerSummaryDto;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.HandlingSessionDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.repo.AssignmentRepository;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.HandlingSessionRepository;
import com.omnichannel.support.repo.MessageRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceService {

    private final JtbdService jtbdService;
    private final TaskService taskService;
    private final CustomerConversationService customerConversationService;
    private final ConversationService conversationService;
    private final DocumentService documentService;
    private final AssignmentRepository assignmentRepository;
    private final HandlingSessionRepository handlingSessionRepository;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final MessageRepository messageRepository;
    private final DocumentLinkService documentLinkService;

    @Transactional(readOnly = true)
    public List<CustomerSummaryDto> listCustomers() {
        return jtbdService.listCustomers();
    }

    @Transactional(readOnly = true)
    public AgentCustomerWorkspaceDto getWorkspace(String customerId) {
        Conversation conversation = customerConversationService.findByCustomerId(customerId);
        if (conversation == null) {
            throw new NotFoundException("customer conversation not found");
        }

        List<Task> rawTasks = taskService.findTasksForCustomerEntities(customerId);
        List<com.omnichannel.support.dto.TaskDto> tasks = rawTasks.stream()
                .map(taskService::toDtoView)
                .toList();
        List<CustomerJtbdDto> jtbds = jtbdService.listCustomerJtbds(customerId);
        List<MessageDto> messages = conversationService.listTimeline(conversation);
        List<DocumentDto> documents = documentService.listByConversation(conversation, null);
        List<CustomerIdentityLink> identityLinks = customerIdentityLinkRepository.findByCustomerId(customerId);

        List<String> emails = identityLinks.stream()
                .filter(link -> link.getIdentifierType() == IdentifierType.EMAIL)
                .map(CustomerIdentityLink::getIdentifierValue)
                .distinct()
                .toList();
        List<String> phones = identityLinks.stream()
                .filter(link -> link.getIdentifierType() == IdentifierType.PHONE)
                .map(CustomerIdentityLink::getIdentifierValue)
                .distinct()
                .toList();

        String defaultTaskId = rawTasks.stream()
                .filter(task -> task.getStatus() != TaskStatus.CLOSED && task.getStatus() != TaskStatus.RESOLVED)
                .max(Comparator.comparing(Task::getCreatedAt))
                .map(Task::getTaskNumber)
                .orElse(rawTasks.stream().max(Comparator.comparing(Task::getCreatedAt)).map(Task::getTaskNumber).orElse(null));

        List<String> domains = rawTasks.stream()
                .map(task -> domainForTask(task))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();

        return new AgentCustomerWorkspaceDto(
                customerId,
                emails,
                phones,
                conversation.getPublicId(),
                conversation.getPrimaryChannel(),
                defaultTaskId,
                domains,
                jtbds,
                tasks,
                messages,
                documents,
                assignmentRepository.findByConversationOrderByAssignedAtDesc(conversation).stream()
                        .map(assignment -> new AgentAssignmentDto(
                                assignment.getAssignedGroup(),
                                assignment.getAssignedAgent(),
                                assignment.getStatus().name(),
                                assignment.getAssignedAt(),
                                assignment.getClosedAt()))
                        .toList(),
                handlingSessionRepository.findByConversationOrderByStartAtDesc(conversation).stream()
                        .map(session -> new HandlingSessionDto(
                                session.getAssignedGroup(),
                                session.getAssignedAgent(),
                                session.getStartAt(),
                                session.getEndAt()))
                        .toList());
    }

    @Transactional
    public MessageDto postConversationMessage(
            String customerId, String agentEmail, String taskId, PostMessageRequest request) {
        Task task = resolveTaskForCustomer(customerId, taskId);
        if (task != null) {
            return taskService.postMessage(
                    task.getTaskNumber(),
                    new PostMessageRequest(
                            request.channel() != null ? request.channel() : task.getSourceChannel(),
                            SenderType.AGENT,
                            agentEmail,
                            request.body(),
                            request.attachmentUrls(),
                            request.externalThreadRef(),
                            mergeMetadata(request.metadata(), Map.of("source", "agent_conversation_workspace"))));
        }

        Conversation conversation = requireConversation(customerId);
        return conversationService.appendMessage(
                conversation,
                activeJtbdForCustomer(customerId),
                null,
                request.channel() != null ? request.channel() : conversation.getPrimaryChannel(),
                SenderType.AGENT,
                agentEmail,
                request.body(),
                request.attachmentUrls(),
                request.externalThreadRef(),
                mergeMetadata(request.metadata(), Map.of("source", "agent_conversation_workspace")));
    }

    @Transactional
    public DocumentDto registerConversationDocument(
            String customerId, String agentEmail, String taskId, RegisterDocumentRequest request) {
        Task task = resolveTaskForCustomer(customerId, taskId);
        if (task != null) {
            return taskService.registerDocument(
                    task.getTaskNumber(),
                    new RegisterDocumentRequest(
                            request.channel() != null ? request.channel() : task.getSourceChannel(),
                            SenderType.AGENT,
                            agentEmail,
                            request.fileUrl(),
                            request.documentType(),
                            request.claimId(),
                            request.policyId(),
                            request.messageBody(),
                            mergeMetadata(request.metadata(), Map.of("source", "agent_conversation_workspace_upload"))));
        }

        Conversation conversation = requireConversation(customerId);
        CustomerJtbd customerJtbd = activeJtbdForCustomer(customerId);
        DocumentDto document = documentService.register(
                conversation,
                customerJtbd,
                null,
                customerId,
                request.channel() != null ? request.channel() : conversation.getPrimaryChannel(),
                request.fileUrl(),
                request.documentType(),
                request.claimId(),
                request.policyId(),
                mergeMetadata(request.metadata(), Map.of("source", "agent_conversation_workspace_upload")));
        MessageDto message = conversationService.appendMessage(
                conversation,
                customerJtbd,
                null,
                request.channel() != null ? request.channel() : conversation.getPrimaryChannel(),
                SenderType.AGENT,
                agentEmail,
                request.messageBody() != null && !request.messageBody().isBlank()
                        ? request.messageBody()
                        : "Agent attached a supporting document.",
                List.of(document.fileUrl()),
                null,
                Map.of("attachment_ids", List.of(document.documentId()), "source", "agent_conversation_workspace_upload"));
        documentLinkService.link(
                documentService.getByPublicId(document.documentId()),
                conversation,
                messageRepository.findByPublicId(message.messageId()).orElse(null),
                customerJtbd,
                null);
        return document;
    }

    private Conversation requireConversation(String customerId) {
        Conversation conversation = customerConversationService.findByCustomerId(customerId);
        if (conversation == null) {
            throw new NotFoundException("customer conversation not found");
        }
        return conversation;
    }

    private Task resolveTaskForCustomer(String customerId, String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            Task task = taskService.loadCanonicalTask(taskId);
            if (!customerId.equals(task.getCustomerId())) {
                throw new NotFoundException("task not found on selected customer");
            }
            return task;
        }
        return taskService.findOpenTasksForCustomer(customerId).stream()
                .max(Comparator.comparing(Task::getCreatedAt))
                .orElse(null);
    }

    private CustomerJtbd activeJtbdForCustomer(String customerId) {
        return jtbdService.activeJtbdsForCustomer(customerId).stream().findFirst().orElse(null);
    }

    private static String domainForTask(Task task) {
        if (task.getAssignedQueue() != null && !task.getAssignedQueue().isBlank()) {
            return humanize(task.getAssignedQueue());
        }
        if (task.getLob() != null && !task.getLob().isBlank()) {
            return humanize(task.getLob());
        }
        return "General";
    }

    private static String humanize(String value) {
        String normalized = value.replace("queue-", "").replace('_', ' ').replace('-', ' ').trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static Map<String, Object> mergeMetadata(Map<String, Object> primary, Map<String, Object> defaults) {
        Map<String, Object> merged = new HashMap<>();
        if (defaults != null) {
            merged.putAll(defaults);
        }
        if (primary != null) {
            merged.putAll(primary);
        }
        return merged;
    }
}
