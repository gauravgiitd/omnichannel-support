package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.JtbdInstanceStatus;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskPriority;
import com.omnichannel.support.domain.TaskStatus;
import com.omnichannel.support.dto.CreateAuthenticatedTaskRequest;
import com.omnichannel.support.dto.CreateTaskRequest;
import com.omnichannel.support.dto.CustomerRequestDto;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.CustomerJtbdRepository;
import com.omnichannel.support.repo.TaskMergeMapRepository;
import com.omnichannel.support.repo.TaskRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerRequestService {

    private final TaskRepository taskRepository;
    private final TaskMergeMapRepository taskMergeMapRepository;
    private final TaskResolutionService taskResolutionService;
    private final TaskService taskService;
    private final CustomerJtbdRepository customerJtbdRepository;
    private final JtbdService jtbdService;
    private final CustomerConversationService customerConversationService;
    private final ConversationService conversationService;
    private final DocumentService documentService;

    @Transactional(readOnly = true)
    public List<CustomerRequestDto> listRequestsForCustomer(String customerId) {
        return groupedRequests(customerId).values().stream()
                .map(RequestAggregate::toDto)
                .sorted(Comparator.comparing(CustomerRequestDto::updatedAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerRequestDto getRequestForCustomer(String customerId, String requestId) {
        return resolveRequest(customerId, requestId).toDto();
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listMessages(String customerId, String requestId) {
        RequestAggregate aggregate = resolveRequest(customerId, requestId);
        return conversationService.listTimeline(aggregate.conversation, aggregate.customerJtbd);
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listDocuments(String customerId, String requestId) {
        RequestAggregate aggregate = resolveRequest(customerId, requestId);
        return documentService.listByConversation(aggregate.conversation, aggregate.customerJtbd);
    }

    @Transactional
    public CustomerRequestDto createRequest(
            String customerId, String customerEmail, CreateAuthenticatedTaskRequest request) {
        CustomerJtbd customerJtbd = jtbdService.createGeneralSupportJtbd(customerId);
        CreateTaskRequest createTaskRequest = new CreateTaskRequest(
                customerId,
                request.issueType(),
                request.lob(),
                request.claimId(),
                request.policyId(),
                request.priority(),
                request.sourceChannel(),
                request.initialMessageBody(),
                customerEmail,
                request.initialMessageMetadata(),
                request.initialExternalThreadRef());
        taskService.createTask(createTaskRequest, customerJtbd);
        return getRequestForCustomer(customerId, CustomerRequestIds.forJtbd(customerJtbd));
    }

    @Transactional
    public MessageDto postMessage(String customerId, String customerEmail, String requestId, PostMessageRequest request) {
        RequestAggregate aggregate = resolveRequest(customerId, requestId);
        Task target = latestOpenTask(aggregate);
        if (target != null) {
            return taskService.postMessage(
                    target.getTaskNumber(),
                    new PostMessageRequest(
                            request.channel(),
                            SenderType.CUSTOMER,
                            customerEmail,
                            request.body(),
                            request.attachmentUrls(),
                            request.externalThreadRef(),
                            request.metadata()));
        }
        return conversationService.appendMessage(
                aggregate.conversation,
                aggregate.customerJtbd,
                null,
                request.channel(),
                SenderType.CUSTOMER,
                customerEmail,
                request.body(),
                request.attachmentUrls(),
                request.externalThreadRef(),
                request.metadata());
    }

    @Transactional
    public DocumentDto registerDocument(
            String customerId, String customerEmail, String requestId, RegisterDocumentRequest request) {
        RequestAggregate aggregate = resolveRequest(customerId, requestId);
        Task target = latestOpenTask(aggregate);
        if (target != null) {
            return taskService.registerDocument(
                    target.getTaskNumber(),
                    new RegisterDocumentRequest(
                            request.channel(),
                            SenderType.CUSTOMER,
                            customerEmail,
                            request.fileUrl(),
                            request.documentType(),
                            request.claimId(),
                            request.policyId(),
                            request.messageBody(),
                            request.metadata()));
        }
        DocumentDto doc = documentService.register(
                aggregate.conversation,
                aggregate.customerJtbd,
                null,
                customerId,
                request.channel(),
                request.fileUrl(),
                request.documentType(),
                request.claimId(),
                request.policyId(),
                request.metadata());
        conversationService.appendMessage(
                aggregate.conversation,
                aggregate.customerJtbd,
                null,
                request.channel(),
                SenderType.CUSTOMER,
                customerEmail,
                request.messageBody() != null && !request.messageBody().isBlank()
                        ? request.messageBody()
                        : "Customer shared a document about this request.",
                List.of(doc.fileUrl()),
                null,
                Map.of("attachment_ids", List.of(doc.documentId())));
        return doc;
    }

    private Task latestOpenTask(RequestAggregate aggregate) {
        return aggregate.tasks.stream()
                .filter(task -> !isClosed(task.getStatus()))
                .max(Comparator.comparing(Task::getCreatedAt))
                .orElse(null);
    }

    private RequestAggregate resolveRequest(String customerId, String requestId) {
        if (CustomerRequestIds.isJtbdRequest(requestId)) {
            String jtbdPublicId = CustomerRequestIds.extractReference(requestId);
            CustomerJtbd customerJtbd = customerJtbdRepository.findByPublicId(jtbdPublicId)
                    .orElseThrow(() -> new NotFoundException("request not found"));
            if (!customerJtbd.getCustomerId().equals(customerId)) {
                throw new ValidationException("request does not belong to signed-in customer");
            }
            List<Task> tasks = canonicalTasks(
                    taskRepository.findByCustomerJtbdIdOrderByCreatedAtDesc(customerJtbd.getId()),
                    task -> task.getCustomerJtbd() != null
                            && task.getCustomerJtbd().getId().equals(customerJtbd.getId()));
            return RequestAggregate.forJtbd(customerJtbd, tasks, conversationFor(customerId));
        }

        String taskNumber = CustomerRequestIds.extractReference(requestId);
        Task task = taskService.loadCanonicalTask(taskNumber);
        if (!task.getCustomerId().equals(customerId)) {
            throw new ValidationException("request does not belong to signed-in customer");
        }
        if (task.getCustomerJtbd() != null) {
            return resolveRequest(customerId, CustomerRequestIds.forJtbd(task.getCustomerJtbd()));
        }
        return RequestAggregate.forStandaloneTask(task, conversationFor(customerId));
    }

    private Map<String, RequestAggregate> groupedRequests(String customerId) {
        Map<String, RequestAggregate> grouped = new LinkedHashMap<>();
        for (Task task : canonicalTasks(taskRepository.findByCustomerIdOrderByCreatedAtDesc(customerId), any())) {
            if (task.getCustomerJtbd() != null) {
                String requestId = CustomerRequestIds.forJtbd(task.getCustomerJtbd());
                grouped.computeIfAbsent(
                                requestId,
                                ignored -> RequestAggregate.forJtbd(
                                        task.getCustomerJtbd(), new ArrayList<>(), task.getConversation()))
                        .tasks.add(task);
            } else {
                String requestId = CustomerRequestIds.forTask(task);
                grouped.putIfAbsent(requestId, RequestAggregate.forStandaloneTask(task, task.getConversation()));
            }
        }

        for (CustomerJtbd customerJtbd : customerJtbdRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)) {
            grouped.computeIfAbsent(
                    CustomerRequestIds.forJtbd(customerJtbd),
                    ignored -> RequestAggregate.forJtbd(customerJtbd, new ArrayList<>(), conversationFor(customerId)));
        }
        return grouped;
    }

    private Conversation conversationFor(String customerId) {
        return customerConversationService.findByCustomerId(customerId);
    }

    private List<Task> canonicalTasks(List<Task> rawTasks, Predicate<Task> includeFilter) {
        return rawTasks.stream()
                .filter(task -> !taskMergeMapRepository.existsByMergedTask(task))
                .map(taskResolutionService::resolveCanonical)
                .filter(includeFilter)
                .distinct()
                .sorted(Comparator.comparing(Task::getCreatedAt).reversed())
                .toList();
    }

    private static Predicate<Task> any() {
        return task -> true;
    }

    private static boolean isClosed(TaskStatus status) {
        return status == TaskStatus.CLOSED || status == TaskStatus.RESOLVED;
    }

    private static String toIssueType(String title) {
        if (title == null || title.isBlank()) {
            return "general_support_request";
        }
        return title.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static String statusLabelForTask(Task task) {
        return isClosed(task.getStatus()) ? "Completed" : "Active";
    }

    private static String stageLabelForTask(Task task) {
        return humanize(task.getStatus().name());
    }

    private static String titleForTask(Task task) {
        if (task.getIssueType() != null && !task.getIssueType().isBlank()) {
            return humanize(task.getIssueType());
        }
        return "Support request";
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static final class RequestAggregate {

        private final String requestId;
        private final String customerId;
        private final String title;
        private final Conversation conversation;
        private final CustomerJtbd customerJtbd;
        private final List<Task> tasks;

        private RequestAggregate(
                String requestId,
                String customerId,
                String title,
                Conversation conversation,
                CustomerJtbd customerJtbd,
                List<Task> tasks) {
            this.requestId = requestId;
            this.customerId = customerId;
            this.title = title;
            this.conversation = conversation;
            this.customerJtbd = customerJtbd;
            this.tasks = tasks;
        }

        static RequestAggregate forJtbd(CustomerJtbd customerJtbd, List<Task> tasks, Conversation conversation) {
            return new RequestAggregate(
                    CustomerRequestIds.forJtbd(customerJtbd),
                    customerJtbd.getCustomerId(),
                    customerJtbd.getJtbdType().getName(),
                    conversation,
                    customerJtbd,
                    tasks);
        }

        static RequestAggregate forStandaloneTask(Task task, Conversation conversation) {
            List<Task> tasks = new ArrayList<>();
            tasks.add(task);
            return new RequestAggregate(
                    CustomerRequestIds.forTask(task),
                    task.getCustomerId(),
                    titleForTask(task),
                    conversation,
                    null,
                    tasks);
        }

        CustomerRequestDto toDto() {
            Task latestTask = tasks.stream()
                    .max(Comparator.comparing(Task::getUpdatedAt))
                    .orElse(null);
            String stageLabel = customerJtbd != null
                    ? customerJtbd.getCurrentStage().getStageName()
                    : latestTask != null ? stageLabelForTask(latestTask) : "Open";
            String statusLabel = customerJtbd != null
                    ? (customerJtbd.getStatus() == JtbdInstanceStatus.COMPLETED ? "Completed" : "Active")
                    : latestTask != null ? statusLabelForTask(latestTask) : "Active";
            ChannelType sourceChannel = latestTask != null ? latestTask.getSourceChannel() : ChannelType.UI;
            java.time.Instant createdAt = tasks.stream()
                    .map(Task::getCreatedAt)
                    .min(Comparator.naturalOrder())
                    .orElse(customerJtbd != null ? customerJtbd.getCreatedAt() : java.time.Instant.now());
            java.time.Instant updatedAt = tasks.stream()
                    .map(Task::getUpdatedAt)
                    .max(Comparator.naturalOrder())
                    .orElse(customerJtbd != null ? customerJtbd.getUpdatedAt() : createdAt);

            return new CustomerRequestDto(
                    requestId,
                    customerId,
                    title,
                    stageLabel,
                    statusLabel,
                    sourceChannel,
                    customerJtbd != null,
                    customerJtbd != null ? customerJtbd.getPublicId() : null,
                    customerJtbd != null ? customerJtbd.getJtbdType().getName() : null,
                    customerJtbd != null ? customerJtbd.getCurrentStage().getStageName() : null,
                    customerJtbd != null ? customerJtbd.getStatus().name() : null,
                    tasks.size(),
                    createdAt,
                    updatedAt);
        }
    }
}
