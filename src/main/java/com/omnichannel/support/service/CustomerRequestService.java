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
import com.omnichannel.support.dto.CustomerVisibleJtbdDto;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.CustomerJtbdRepository;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TaskMergeMapRepository;
import com.omnichannel.support.repo.TaskRepository;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final MessageRepository messageRepository;
    private final JtbdService jtbdService;
    private final CustomerConversationService customerConversationService;
    private final ConversationService conversationService;
    private final DocumentService documentService;
    private final DocumentLinkService documentLinkService;

    @Transactional(readOnly = true)
    public List<CustomerRequestDto> listRequestsForCustomer(String customerId) {
        Conversation conversation = conversationFor(customerId);
        if (conversation == null) {
            return List.of();
        }
        return List.of(buildConversationAggregate(customerId, conversation).toDto());
    }

    @Transactional(readOnly = true)
    public CustomerRequestDto getRequestForCustomer(String customerId, String requestId) {
        return resolveRequest(customerId, requestId).toDto();
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listMessages(String customerId, String requestId) {
        RequestAggregate aggregate = resolveRequest(customerId, requestId);
        if (CustomerRequestIds.isConversationRequest(requestId)) {
            return conversationService.listCustomerVisibleTimeline(aggregate.conversation);
        }
        return conversationService.listCustomerVisibleTimeline(aggregate.conversation, aggregate.customerJtbd);
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listDocuments(String customerId, String requestId) {
        RequestAggregate aggregate = resolveRequest(customerId, requestId);
        if (CustomerRequestIds.isConversationRequest(requestId)) {
            return documentService.listCustomerVisibleByConversation(aggregate.conversation, null);
        }
        return documentService.listCustomerVisibleByConversation(aggregate.conversation, aggregate.customerJtbd);
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
        MessageDto message = conversationService.appendMessage(
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
        documentLinkService.link(
                documentService.getByPublicId(doc.documentId()),
                null,
                messageRepository.findByPublicId(message.messageId()).orElse(null),
                aggregate.customerJtbd,
                null);
        return doc;
    }

    private Task latestOpenTask(RequestAggregate aggregate) {
        return aggregate.tasks.stream()
                .filter(task -> !isClosed(task.getStatus()))
                .max(Comparator.comparing(Task::getCreatedAt))
                .orElse(null);
    }

    private RequestAggregate resolveRequest(String customerId, String requestId) {
        if (CustomerRequestIds.isConversationRequest(requestId)) {
            Conversation conversation = conversationFor(customerId);
            if (conversation == null || !conversation.getPublicId().equals(CustomerRequestIds.extractReference(requestId))) {
                throw new NotFoundException("request not found");
            }
            return buildConversationAggregate(customerId, conversation);
        }
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
        Conversation conversation = conversationFor(customerId);
        if (conversation != null) {
            return buildConversationAggregate(customerId, conversation);
        }
        return RequestAggregate.forStandaloneTask(task, null);
    }

    private RequestAggregate buildConversationAggregate(String customerId, Conversation conversation) {
        List<Task> tasks = canonicalTasks(taskRepository.findByCustomerIdOrderByCreatedAtDesc(customerId), any());
        List<CustomerJtbd> customerJtbds = customerJtbdRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        CustomerJtbd displayJtbd = customerJtbds.stream()
                .filter(jtbd -> jtbd.getStatus() != JtbdInstanceStatus.COMPLETED)
                .findFirst()
                .orElse(customerJtbds.isEmpty() ? null : customerJtbds.get(0));
        return RequestAggregate.forConversation(conversation, customerId, displayJtbd, tasks, customerJtbds);
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
        private final List<CustomerJtbd> customerJtbds;

        private RequestAggregate(
                String requestId,
                String customerId,
                String title,
                Conversation conversation,
                CustomerJtbd customerJtbd,
                List<Task> tasks,
                List<CustomerJtbd> customerJtbds) {
            this.requestId = requestId;
            this.customerId = customerId;
            this.title = title;
            this.conversation = conversation;
            this.customerJtbd = customerJtbd;
            this.tasks = tasks;
            this.customerJtbds = customerJtbds;
        }

        static RequestAggregate forConversation(
                Conversation conversation,
                String customerId,
                CustomerJtbd displayJtbd,
                List<Task> tasks,
                List<CustomerJtbd> customerJtbds) {
            return new RequestAggregate(
                    CustomerRequestIds.forConversation(conversation),
                    customerId,
                    "Your support conversation",
                    conversation,
                    displayJtbd,
                    tasks,
                    customerJtbds);
        }

        static RequestAggregate forJtbd(CustomerJtbd customerJtbd, List<Task> tasks, Conversation conversation) {
            return new RequestAggregate(
                    CustomerRequestIds.forJtbd(customerJtbd),
                    customerJtbd.getCustomerId(),
                    customerJtbd.getJtbdType().getName(),
                    conversation,
                    customerJtbd,
                    tasks,
                    List.of(customerJtbd));
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
                    tasks,
                    List.of());
        }

        CustomerRequestDto toDto() {
            Task latestTask = tasks.stream()
                    .max(Comparator.comparing(Task::getUpdatedAt))
                    .orElse(null);
            long activeJtbdCount = customerJtbds.stream()
                    .filter(jtbd -> jtbd.getStatus() != JtbdInstanceStatus.COMPLETED)
                    .count();
            String stageLabel = customerJtbd != null
                    ? customerJtbd.getCurrentStage().getStageName()
                    : activeJtbdCount > 0 ? activeJtbdCount + " active request" + (activeJtbdCount == 1 ? "" : "s")
                    : latestTask != null ? stageLabelForTask(latestTask) : "Conversation";
            String statusLabel = customerJtbd != null
                    ? (customerJtbd.getStatus() == JtbdInstanceStatus.COMPLETED ? "Completed" : "Active")
                    : activeJtbdCount > 0 ? "Active" : latestTask != null ? statusLabelForTask(latestTask) : "Open";
            ChannelType sourceChannel = latestTask != null
                    ? latestTask.getSourceChannel()
                    : conversation != null ? conversation.getPrimaryChannel() : ChannelType.UI;
            java.time.Instant createdAt = tasks.stream()
                    .map(Task::getCreatedAt)
                    .min(Comparator.naturalOrder())
                    .orElse(conversation != null ? conversation.getCreatedAt()
                            : customerJtbd != null ? customerJtbd.getCreatedAt() : java.time.Instant.now());
            java.time.Instant updatedAt = tasks.stream()
                    .map(Task::getUpdatedAt)
                    .max(Comparator.naturalOrder())
                    .orElse(conversation != null ? conversation.getUpdatedAt()
                            : customerJtbd != null ? customerJtbd.getUpdatedAt() : createdAt);

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
                    customerJtbds.stream()
                            .map(jtbd -> new CustomerVisibleJtbdDto(
                                    jtbd.getPublicId(),
                                    jtbd.getJtbdType().getName(),
                                    jtbd.getCurrentStage().getStageName(),
                                    jtbd.getStatus().name(),
                                    jtbd.getStatus() == JtbdInstanceStatus.COMPLETED))
                            .toList(),
                    tasks.size(),
                    createdAt,
                    updatedAt);
        }
    }
}
