package com.omnichannel.support.service;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.ExecutionTier;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskPriority;
import com.omnichannel.support.domain.TaskStatus;
import com.omnichannel.support.domain.TaskType;
import com.omnichannel.support.config.WhatsAppCloudApiProperties;
import com.omnichannel.support.dto.AgentAssignmentDto;
import com.omnichannel.support.dto.AgentCustomerWorkspaceDto;
import com.omnichannel.support.dto.CreateExpertTaskRequest;
import com.omnichannel.support.dto.CreateCustomerJtbdRequest;
import com.omnichannel.support.dto.CreateTaskRequest;
import com.omnichannel.support.dto.CustomerJtbdDto;
import com.omnichannel.support.dto.CustomerSummaryDto;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.HandlingSessionDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.dto.WhatsAppCallEventDto;
import com.omnichannel.support.dto.WhatsAppCallControlDto;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.repo.AssignmentRepository;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.CustomerJtbdRepository;
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
    private final WhatsAppCloudApiProperties whatsAppCloudApiProperties;
    private final CustomerConversationService customerConversationService;
    private final CustomerChannelNotificationService customerChannelNotificationService;
    private final ConversationChannelReplyService conversationChannelReplyService;
    private final ConversationService conversationService;
    private final DocumentService documentService;
    private final AssignmentRepository assignmentRepository;
    private final HandlingSessionRepository handlingSessionRepository;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final CustomerJtbdRepository customerJtbdRepository;
    private final MessageRepository messageRepository;
    private final DocumentLinkService documentLinkService;
    private final WhatsAppCallingService whatsAppCallingService;

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
        List<MessageDto> messages = conversationService.listCustomerVisibleTimeline(conversation);
        List<DocumentDto> documents = documentService.listCustomerVisibleByConversation(conversation, null);
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
        String primaryPhone = phones.isEmpty() ? null : phones.get(0);

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
        WhatsAppCallingService.PermissionState permissionState = whatsAppCallingService.currentPermissionState(customerId);

        return new AgentCustomerWorkspaceDto(
                customerId,
                emails,
                phones,
                primaryPhone,
                whatsAppCloudApiProperties.isCallingEnabled() && primaryPhone != null && !primaryPhone.isBlank(),
                "/webhooks/meta/whatsapp",
                permissionState.status(),
                permissionState.updatedAt(),
                permissionState.expiresAt(),
                permissionState.granted(),
                conversation.getPublicId(),
                conversation.getActiveCustomerJtbdPublicId(),
                conversation.getPrimaryChannel(),
                defaultTaskId,
                domains,
                jtbds,
                tasks,
                messages,
                documents,
                whatsAppCallingService.listRecentForCustomer(customerId),
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
    public WhatsAppCallEventDto requestWhatsAppCallPermission(String customerId, String agentEmail) {
        Conversation conversation = requireConversation(customerId);
        String recipient = customerIdentityLinkRepository
                .findFirstByCustomerIdAndIdentifierTypeOrderByCreatedAtAsc(customerId, IdentifierType.PHONE)
                .map(CustomerIdentityLink::getIdentifierValue)
                .orElseThrow(() -> new NotFoundException("customer phone not found"));
        if (whatsAppCloudApiProperties.getCallPermissionTemplateName() == null
                || whatsAppCloudApiProperties.getCallPermissionTemplateName().isBlank()) {
            throw new NotFoundException("whatsapp call permission template is not configured");
        }
        CustomerChannelNotificationService.DirectDeliveryResult delivery =
                customerChannelNotificationService.sendWhatsAppTemplate(
                        recipient,
                        whatsAppCloudApiProperties.getCallPermissionTemplateName(),
                        whatsAppCloudApiProperties.getCallPermissionTemplateLanguage());
        CustomerJtbd activeJtbd = activeJtbdForConversation(conversation);
        conversationService.appendMessage(
                conversation,
                activeJtbd,
                null,
                com.omnichannel.support.domain.ChannelType.WHATSAPP,
                SenderType.AGENT,
                agentEmail,
                "Agent requested WhatsApp calling permission.",
                List.of(),
                delivery.externalThreadRef(),
                Map.of(
                        "source", "agent_whatsapp_call_permission_request",
                        "delivery", "whatsapp",
                        "recipient", recipient,
                        "template", whatsAppCloudApiProperties.getCallPermissionTemplateName(),
                        ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_CUSTOMER));
        return toCallEventDto(whatsAppCallingService.recordPermissionRequest(
                customerId,
                recipient,
                agentEmail,
                delivery.externalThreadRef()));
    }

    @Transactional
    public WhatsAppCallControlDto initiateOutgoingWhatsAppCall(
            String customerId, String sdpType, String sdp, String agentEmail) {
        String recipient = customerIdentityLinkRepository
                .findFirstByCustomerIdAndIdentifierTypeOrderByCreatedAtAsc(customerId, IdentifierType.PHONE)
                .map(CustomerIdentityLink::getIdentifierValue)
                .orElseThrow(() -> new NotFoundException("customer phone not found"));
        return whatsAppCallingService.initiateOutgoingCall(customerId, recipient, sdpType, sdp, agentEmail);
    }

    @Transactional(readOnly = true)
    public WhatsAppCallControlDto getWhatsAppCall(String customerId, String callId) {
        return whatsAppCallingService.getCallControl(customerId, callId);
    }

    @Transactional
    public WhatsAppCallControlDto preAcceptWhatsAppCall(
            String customerId, String callId, String sdpType, String sdp, String agentEmail) {
        return whatsAppCallingService.preAcceptCall(customerId, callId, sdpType, sdp, agentEmail);
    }

    @Transactional
    public WhatsAppCallControlDto acceptWhatsAppCall(
            String customerId, String callId, String sdpType, String sdp, String agentEmail) {
        return whatsAppCallingService.acceptCall(customerId, callId, sdpType, sdp, agentEmail);
    }

    @Transactional
    public WhatsAppCallControlDto rejectWhatsAppCall(String customerId, String callId, String agentEmail) {
        return whatsAppCallingService.rejectCall(customerId, callId, agentEmail);
    }

    @Transactional
    public WhatsAppCallControlDto terminateWhatsAppCall(String customerId, String callId, String agentEmail) {
        return whatsAppCallingService.terminateCall(customerId, callId, agentEmail);
    }

    private WhatsAppCallEventDto toCallEventDto(com.omnichannel.support.domain.WhatsAppCall call) {
        return new WhatsAppCallEventDto(
                call.getCallId(),
                call.getFromPhone(),
                call.getToPhone(),
                call.getStatus(),
                call.getDirection(),
                call.getEvent(),
                call.getPermissionStatus(),
                call.getSessionSdpType(),
                call.getSessionSdp() != null && !call.getSessionSdp().isBlank(),
                call.getUpdatedAt());
    }

    @Transactional
    public MessageDto postConversationMessage(
            String customerId, String agentEmail, String taskId, PostMessageRequest request) {
        Conversation conversation = requireConversation(customerId);
        CustomerJtbd activeJtbd = activeJtbdForConversation(conversation);
        Task task = resolveTaskForCustomer(customerId, taskId);
        if (activeJtbd != null) {
            task = resolveTaskForActiveJtbd(activeJtbd, task);
        }
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
                            mergeMetadata(request.metadata(), Map.of(
                                    "source", "agent_conversation_workspace",
                                    ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_CUSTOMER))));
        }

        Map<String, Object> metadata = new HashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        ChannelType channel = request.channel() != null ? request.channel() : conversation.getPrimaryChannel();
        String externalThreadRef = request.externalThreadRef();
        try {
            ConversationChannelReplyService.OutboundDeliveryResult delivery =
                    conversationChannelReplyService.deliverAgentReply(conversation, customerId, agentEmail, request.body());
            if (delivery.metadata() != null) {
                metadata.putAll(delivery.metadata());
            }
            if (delivery.channel() != null) {
                channel = delivery.channel();
            }
            externalThreadRef = delivery.externalThreadRef();
            metadata.put("delivery_status", "sent");
        } catch (com.omnichannel.support.error.ValidationException ex) {
            metadata.put("delivery_status", "failed");
            metadata.put("delivery_error", ex.getMessage());
            metadata.put("delivery_channel", channel != null ? channel.name() : conversation.getPrimaryChannel().name());
        }

        return conversationService.appendMessage(
                conversation,
                activeJtbd,
                null,
                channel,
                SenderType.AGENT,
                agentEmail,
                request.body(),
                request.attachmentUrls(),
                externalThreadRef,
                mergeMetadata(metadata, Map.of(
                        "source", "agent_conversation_workspace",
                        ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_CUSTOMER)));
    }

    @Transactional
    public DocumentDto registerConversationDocument(
            String customerId, String agentEmail, String taskId, RegisterDocumentRequest request) {
        Conversation conversation = requireConversation(customerId);
        CustomerJtbd activeJtbd = activeJtbdForConversation(conversation);
        Task task = resolveTaskForCustomer(customerId, taskId);
        if (activeJtbd != null) {
            task = resolveTaskForActiveJtbd(activeJtbd, task);
        }
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
                            mergeMetadata(request.metadata(), Map.of(
                                    "source", "agent_conversation_workspace_upload",
                                    ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_CUSTOMER))));
        }

        Map<String, Object> deliveryMeta = new HashMap<>();
        if (request.metadata() != null) {
            deliveryMeta.putAll(request.metadata());
        }
        DocumentDto document = documentService.register(
                conversation,
                activeJtbd,
                null,
                customerId,
                request.channel() != null ? request.channel() : conversation.getPrimaryChannel(),
                request.fileUrl(),
                request.documentType(),
                request.claimId(),
                request.policyId(),
                mergeMetadata(request.metadata(), Map.of(
                        "source", "agent_conversation_workspace_upload",
                        ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_CUSTOMER)));
        try {
            ConversationChannelReplyService.OutboundDeliveryResult delivery =
                    conversationChannelReplyService.deliverAgentDocument(
                            conversation,
                            customerId,
                            agentEmail,
                            document,
                            request.messageBody());
            if (delivery.metadata() != null) {
                deliveryMeta.putAll(delivery.metadata());
            }
            deliveryMeta.put("delivery_status", "sent");
        } catch (com.omnichannel.support.error.ValidationException ex) {
            deliveryMeta.put("delivery_status", "failed");
            deliveryMeta.put("delivery_error", ex.getMessage());
            deliveryMeta.put("delivery_channel", conversation.getPrimaryChannel().name());
        }
        MessageDto message = conversationService.appendMessage(
                conversation,
                activeJtbd,
                null,
                deliveryMeta.get("delivery") instanceof String deliveryType && deliveryType.startsWith("email")
                        ? com.omnichannel.support.domain.ChannelType.EMAIL
                        : deliveryMeta.get("delivery") instanceof String deliveryType && deliveryType.startsWith("whatsapp")
                                ? com.omnichannel.support.domain.ChannelType.WHATSAPP
                                : request.channel() != null ? request.channel() : conversation.getPrimaryChannel(),
                SenderType.AGENT,
                agentEmail,
                request.messageBody() != null && !request.messageBody().isBlank()
                        ? request.messageBody()
                        : "Agent attached a supporting document.",
                List.of(document.fileUrl()),
                null,
                mergeMetadata(deliveryMeta, Map.of(
                        "attachment_ids", List.of(document.documentId()),
                        "source", "agent_conversation_workspace_upload",
                        ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_CUSTOMER)));
        documentLinkService.link(
                documentService.getByPublicId(document.documentId()),
                conversation,
                messageRepository.findByPublicId(message.messageId()).orElse(null),
                activeJtbd,
                null);
        return document;
    }

    @Transactional(readOnly = true)
    public List<com.omnichannel.support.dto.JtbdTypeDto> listJtbdTypes() {
        return jtbdService.listTypes();
    }

    @Transactional
    public CustomerJtbdDto createConversationJtbd(String customerId, CreateCustomerJtbdRequest request) {
        CustomerJtbdDto created = jtbdService.createCustomerJtbd(customerId, request);
        Conversation conversation = requireConversation(customerId);
        CustomerJtbd jtbd = jtbdService.loadCustomerJtbd(created.publicId());
        customerConversationService.setActiveCustomerJtbd(conversation, jtbd);
        return created;
    }

    @Transactional
    public CustomerJtbdDto activateConversationJtbd(String customerId, String customerJtbdId) {
        Conversation conversation = requireConversation(customerId);
        CustomerJtbd jtbd = jtbdService.loadCustomerJtbd(customerJtbdId);
        if (!customerId.equals(jtbd.getCustomerId())) {
            throw new NotFoundException("jtbd not found on selected customer");
        }
        customerConversationService.setActiveCustomerJtbd(conversation, jtbd);
        return jtbdService.toCustomerJtbdDtoView(jtbd);
    }

    @Transactional
    public CustomerJtbdDto deactivateConversationJtbd(String customerId, String customerJtbdId) {
        Conversation conversation = requireConversation(customerId);
        CustomerJtbd jtbd = jtbdService.loadCustomerJtbd(customerJtbdId);
        if (!customerId.equals(jtbd.getCustomerId())) {
            throw new NotFoundException("jtbd not found on selected customer");
        }
        if (customerJtbdId.equals(conversation.getActiveCustomerJtbdPublicId())) {
            customerConversationService.clearActiveCustomerJtbd(conversation);
        }
        return jtbdService.toCustomerJtbdDtoView(jtbd);
    }

    @Transactional
    public CustomerJtbdDto completeConversationJtbd(String customerId, String customerJtbdId) {
        Conversation conversation = requireConversation(customerId);
        CustomerJtbdDto completed = jtbdService.completeCustomerJtbd(customerJtbdId);
        if (customerJtbdId.equals(conversation.getActiveCustomerJtbdPublicId())) {
            customerConversationService.clearActiveCustomerJtbd(conversation);
        }
        return completed;
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listInternalTaskMessages(String customerId, String taskId) {
        Task task = requireTaskOnCustomer(customerId, taskId);
        return taskService.listInternalMessages(task.getTaskNumber());
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listInternalTaskDocuments(String customerId, String taskId) {
        Task task = requireTaskOnCustomer(customerId, taskId);
        return taskService.listInternalDocuments(task.getTaskNumber());
    }

    @Transactional
    public MessageDto postInternalTaskMessage(
            String customerId, String agentEmail, String taskId, PostMessageRequest request) {
        Task task = requireTaskOnCustomer(customerId, taskId);
        return taskService.postInternalMessage(
                task.getTaskNumber(),
                new PostMessageRequest(
                        request.channel() != null ? request.channel() : com.omnichannel.support.domain.ChannelType.UI,
                        SenderType.AGENT,
                        agentEmail,
                        request.body(),
                        request.attachmentUrls(),
                        request.externalThreadRef(),
                        mergeMetadata(request.metadata(), Map.of(
                                "source", "agent_internal_task_workspace",
                                ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_INTERNAL))));
    }

    @Transactional
    public DocumentDto registerInternalTaskDocument(
            String customerId, String agentEmail, String taskId, RegisterDocumentRequest request) {
        Task task = requireTaskOnCustomer(customerId, taskId);
        return taskService.registerInternalDocument(
                task.getTaskNumber(),
                new RegisterDocumentRequest(
                        request.channel() != null ? request.channel() : com.omnichannel.support.domain.ChannelType.UI,
                        SenderType.AGENT,
                        agentEmail,
                        request.fileUrl(),
                        request.documentType(),
                        request.claimId(),
                        request.policyId(),
                        request.messageBody(),
                        mergeMetadata(request.metadata(), Map.of(
                                "source", "agent_internal_task_workspace_upload",
                                ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_INTERNAL))));
    }

    @Transactional
    public com.omnichannel.support.dto.TaskDto createExpertTask(
            String customerId, String agentEmail, CreateExpertTaskRequest request) {
        CustomerJtbd customerJtbd = customerJtbdRepository.findByPublicId(request.customerJtbdId())
                .orElseThrow(() -> new NotFoundException("jtbd not found"));
        if (!customerId.equals(customerJtbd.getCustomerId())) {
            throw new NotFoundException("jtbd not found on selected customer");
        }
        Task seedTask = resolveTaskForCustomer(customerId, null);
        com.omnichannel.support.domain.ChannelType sourceChannel =
                seedTask != null ? seedTask.getSourceChannel() : requireConversation(customerId).getPrimaryChannel();
        String issueType = request.issueType() != null && !request.issueType().isBlank()
                ? request.issueType().trim()
                : customerJtbd.getJtbdType().getName() + " task";
        String assignedQueue = request.assignedQueue() != null && !request.assignedQueue().isBlank()
                ? request.assignedQueue().trim()
                : "queue-expert-work";
        TaskPriority priority = request.priority() != null ? request.priority() : TaskPriority.MEDIUM;
        Task created = taskService.createInternalTask(
                new CreateTaskRequest(
                        customerId,
                        issueType,
                        request.lob(),
                        request.claimId(),
                        request.policyId(),
                        priority,
                        sourceChannel,
                        request.body(),
                        agentEmail,
                        Map.of(
                                "source", "agent_explicit_expert_task_create",
                                ConversationService.AUDIENCE_KEY, ConversationService.AUDIENCE_INTERNAL),
                        null),
                customerJtbd,
                TaskType.EXPERT_TASK,
                ExecutionTier.EXPERT,
                assignedQueue);
        return taskService.toDtoView(created);
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

    private Task requireTaskOnCustomer(String customerId, String taskId) {
        Task task = resolveTaskForCustomer(customerId, taskId);
        if (task == null) {
            throw new NotFoundException("task not found on selected customer");
        }
        return task;
    }

    private CustomerJtbd activeJtbdForCustomer(String customerId) {
        return jtbdService.activeJtbdsForCustomer(customerId).stream().findFirst().orElse(null);
    }

    private CustomerJtbd activeJtbdForConversation(Conversation conversation) {
        return customerConversationService.activeCustomerJtbd(conversation);
    }

    private Task resolveTaskForActiveJtbd(CustomerJtbd customerJtbd, Task fallback) {
        List<Task> openTasks = taskService.findOpenTasksForCustomer(customerJtbd.getCustomerId()).stream()
                .filter(task -> task.getCustomerJtbd() != null && task.getCustomerJtbd().getId().equals(customerJtbd.getId()))
                .sorted(Comparator.comparing(Task::getCreatedAt).reversed())
                .toList();
        if (!openTasks.isEmpty()) {
            return openTasks.get(0);
        }
        if (fallback != null && fallback.getCustomerJtbd() != null && fallback.getCustomerJtbd().getId().equals(customerJtbd.getId())) {
            return fallback;
        }
        return null;
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
