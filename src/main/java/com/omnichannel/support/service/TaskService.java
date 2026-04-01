package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskStatus;
import com.omnichannel.support.dto.CreateTaskRequest;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PatchTaskRequest;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.dto.TaskDto;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.TaskMergeMapRepository;
import com.omnichannel.support.repo.TaskRepository;
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
public class TaskService {

    private static final Set<TaskStatus> OPEN_LIKE =
            EnumSet.of(
                    TaskStatus.OPEN,
                    TaskStatus.ASSIGNED,
                    TaskStatus.PENDING_CUSTOMER,
                    TaskStatus.PENDING_INTERNAL,
                    TaskStatus.REOPENED);

    private final TaskRepository taskRepository;
    private final TaskMergeMapRepository taskMergeMapRepository;
    private final TaskNumberGenerator taskNumberGenerator;
    private final ConversationService conversationService;
    private final TaskResolutionService taskResolutionService;
    private final AuditService auditService;
    private final DocumentService documentService;
    private final RoutingService routingService;
    private final TaskEmailNotificationService taskEmailNotificationService;
    private final TaskOriginReplyService taskOriginReplyService;

    @Transactional
    public TaskDto createTask(CreateTaskRequest request) {
        return createTask(request, null);
    }

    @Transactional
    public TaskDto createTask(CreateTaskRequest request, CustomerJtbd customerJtbd) {
        Task task = new Task();
        task.setTaskNumber(taskNumberGenerator.newTaskNumber());
        task.setCustomerId(request.customerId());
        task.setCustomerJtbd(customerJtbd);
        task.setIssueType(request.issueType().trim());
        task.setLob(blankToNull(request.lob()));
        task.setClaimId(blankToNull(request.claimId()));
        task.setPolicyId(blankToNull(request.policyId()));
        task.setStatus(TaskStatus.OPEN);
        task.setPriority(request.priority());
        task.setSourceChannel(request.sourceChannel());
        task.setAssignedQueue(
                routingService.resolveQueue(
                        RoutingContext.builder()
                                .issueType(task.getIssueType())
                                .lob(task.getLob())
                                .claimId(task.getClaimId())
                                .policyId(task.getPolicyId())
                                .customerId(task.getCustomerId())
                                .build()));
        taskRepository.save(task);

        String sender =
                request.senderIdentifier() != null && !request.senderIdentifier().isBlank()
                        ? request.senderIdentifier()
                        : request.customerId();
        conversationService.appendMessage(
                task,
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
                "Task",
                task.getTaskNumber(),
                "SYSTEM",
                "task-service",
                        java.util.Map.of(
                                "channel",
                                request.sourceChannel().name(),
                                "assigned_queue",
                                task.getAssignedQueue() != null ? task.getAssignedQueue() : ""));

        taskEmailNotificationService.sendTaskCreatedNotifications(task);

        return toDto(task);
    }

    @Transactional
    public TaskDto patchTask(String taskNumber, PatchTaskRequest request) {
        Task task = loadCanonicalTask(taskNumber);
        if (request.issueType() != null && !request.issueType().isBlank()) {
            task.setIssueType(request.issueType().trim());
        }
        if (request.lob() != null) {
            task.setLob(blankToNull(request.lob()));
        }
        if (request.claimId() != null) {
            task.setClaimId(blankToNull(request.claimId()));
        }
        if (request.policyId() != null) {
            task.setPolicyId(blankToNull(request.policyId()));
        }
        if (request.assignedAgent() != null) {
            task.setAssignedAgent(blankToNull(request.assignedAgent()));
        }
        if (request.status() != null) {
            task.setStatus(request.status());
        }
        boolean explicitQueue = request.assignedQueue() != null && !request.assignedQueue().isBlank();
        if (explicitQueue) {
            task.setAssignedQueue(request.assignedQueue().trim());
        } else if (routingFieldsPresentInPatch(request)) {
            task.setAssignedQueue(
                    routingService.resolveQueue(
                            RoutingContext.builder()
                                    .issueType(task.getIssueType())
                                    .lob(task.getLob())
                                    .claimId(task.getClaimId())
                                    .policyId(task.getPolicyId())
                                    .customerId(task.getCustomerId())
                                    .build()));
        }
        taskRepository.save(task);
        auditService.record(
                "TICKET_UPDATED",
                "Task",
                task.getTaskNumber(),
                "SYSTEM",
                "task-service",
                java.util.Map.of("queue", task.getAssignedQueue() != null ? task.getAssignedQueue() : ""));
        return toDto(task);
    }

    private static boolean routingFieldsPresentInPatch(PatchTaskRequest request) {
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
    public List<TaskDto> listAllTasks() {
        return taskRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .filter(t -> !taskMergeMapRepository.existsByMergedTask(t))
                .map(taskResolutionService::resolveCanonical)
                .distinct()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TaskDto getTask(String taskNumber) {
        Task task = loadCanonicalTask(taskNumber);
        return toDto(task);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listMessages(String taskNumber) {
        Task task = loadCanonicalTask(taskNumber);
        return conversationService.listTimeline(task);
    }

    @Transactional
    public MessageDto postMessage(String taskNumber, PostMessageRequest request) {
        Task task = loadCanonicalTask(taskNumber);
        Map<String, Object> metadata = new HashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        ChannelType channel = request.channel();
        String externalThreadRef = request.externalThreadRef();
        if (request.senderType() == SenderType.AGENT) {
            try {
                TaskOriginReplyService.OutboundDeliveryResult delivery =
                        taskOriginReplyService.deliverAgentReply(task, request.senderIdentifier(), request.body());
                if (delivery.metadata() != null) {
                    metadata.putAll(delivery.metadata());
                }
                if (delivery.channel() != null) {
                    channel = delivery.channel();
                }
                externalThreadRef = delivery.externalThreadRef();
                metadata.put("delivery_status", "sent");
            } catch (ValidationException ex) {
                metadata.put("delivery_status", "failed");
                metadata.put("delivery_error", ex.getMessage());
                metadata.put("delivery_channel", task.getSourceChannel().name());
            }
        }
        PostMessageRequest effectiveRequest = new PostMessageRequest(
                channel,
                request.senderType(),
                request.senderIdentifier(),
                request.body(),
                request.attachmentUrls(),
                externalThreadRef,
                metadata);
        MessageDto message =
                conversationService.appendMessage(
                        task,
                        effectiveRequest.channel(),
                        effectiveRequest.senderType(),
                        effectiveRequest.senderIdentifier(),
                        effectiveRequest.body(),
                        effectiveRequest.attachmentUrls() != null ? effectiveRequest.attachmentUrls() : List.of(),
                        effectiveRequest.externalThreadRef(),
                        effectiveRequest.metadata());

        auditService.record(
                "MESSAGE_APPENDED",
                "Message",
                message.messageId(),
                effectiveRequest.senderType().name(),
                effectiveRequest.senderIdentifier(),
                java.util.Map.of("task", task.getTaskNumber(), "channel", effectiveRequest.channel().name()));

        return message;
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listDocuments(String taskNumber) {
        Task task = loadCanonicalTask(taskNumber);
        return documentService.listByTask(task);
    }

    @Transactional
    public DocumentDto registerDocument(String taskNumber, RegisterDocumentRequest request) {
        Task task = loadCanonicalTask(taskNumber);
        Map<String, Object> meta = new HashMap<>();
        if (request.metadata() != null) {
            meta.putAll(request.metadata());
        }
        DocumentDto doc =
                documentService.register(
                        task,
                        task.getCustomerId(),
                        request.channel(),
                        request.fileUrl(),
                        request.documentType(),
                        request.claimId(),
                        request.policyId(),
                        meta);

        if (request.senderType() == SenderType.AGENT) {
            try {
                TaskOriginReplyService.OutboundDeliveryResult delivery =
                        taskOriginReplyService.deliverAgentDocument(
                                task, request.senderIdentifier(), doc, request.messageBody());
                meta.putAll(delivery.metadata());
                meta.put("delivery_status", "sent");
            } catch (ValidationException ex) {
                meta.put("delivery_status", "failed");
                meta.put("delivery_error", ex.getMessage());
                meta.put("delivery_channel", task.getSourceChannel().name());
            }
        }

        Map<String, Object> messageMeta = new HashMap<>();
        messageMeta.put("attachment_ids", List.of(doc.documentId()));
        if (meta.get("delivery") != null) {
            messageMeta.put("delivery", meta.get("delivery"));
        }
        if (meta.get("recipient") != null) {
            messageMeta.put("recipient", meta.get("recipient"));
        }
        conversationService.appendMessage(
                task,
                request.channel(),
                request.senderType(),
                request.senderIdentifier(),
                documentMessageBody(request),
                List.of(doc.fileUrl()),
                null,
                messageMeta);

        auditService.record(
                "DOCUMENT_MESSAGE_APPENDED",
                "Task",
                task.getTaskNumber(),
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
    public Optional<Task> findSingleOpenTaskForCustomer(String customerId) {
        List<Task> open = findOpenTasksForCustomer(customerId);
        if (open.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(taskResolutionService.resolveCanonical(open.get(0)));
    }

    @Transactional(readOnly = true)
    public List<TaskDto> listTasksForCustomer(String customerId) {
        return taskRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .filter(t -> !taskMergeMapRepository.existsByMergedTask(t))
                .map(taskResolutionService::resolveCanonical)
                .distinct()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public Task loadCanonicalTask(String taskNumber) {
        Task task =
                taskRepository
                        .findByTaskNumber(taskNumber)
                        .orElseThrow(() -> new NotFoundException("task not found"));
        return taskResolutionService.resolveCanonical(task);
    }

    private TaskDto toDto(Task task) {
        return new TaskDto(
                task.getTaskNumber(),
                task.getCustomerId(),
                task.getCustomerJtbd() != null ? task.getCustomerJtbd().getPublicId() : null,
                task.getCustomerJtbd() != null ? task.getCustomerJtbd().getJtbdType().getName() : null,
                task.getCustomerJtbd() != null ? task.getCustomerJtbd().getCurrentStage().getStageName() : null,
                task.getCustomerJtbd() != null ? task.getCustomerJtbd().getStatus().name() : null,
                task.getIssueType(),
                task.getLob(),
                task.getClaimId(),
                task.getPolicyId(),
                task.getStatus(),
                task.getPriority(),
                task.getSourceChannel(),
                task.getAssignedQueue(),
                task.getAssignedAgent(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    public List<Task> findOpenTasksForCustomer(String customerId) {
        return taskRepository.findByCustomerIdAndStatusInOrderByCreatedAtDesc(
                customerId, OPEN_LIKE);
    }
}
