package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.PendingSelectionType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskType;
import com.omnichannel.support.domain.ExecutionTier;
import com.omnichannel.support.domain.TaskPriority;
import com.omnichannel.support.dto.CreateTaskRequest;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.InboundWhatsAppRequest;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.TaskDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TaskRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InboundWhatsAppService {

    private static final java.util.regex.Pattern TICKET_NUMBER =
            java.util.regex.Pattern.compile("(?i)\\b(TSK-[A-Z0-9-]+)\\b");
    private static final int MAX_SELECTION_OPTIONS = 5;

    private final IdentityResolutionService identityResolutionService;
    private final CustomerContactMappingService customerContactMappingService;
    private final MessageRepository messageRepository;
    private final TaskRepository taskRepository;
    private final TaskResolutionService taskResolutionService;
    private final ConversationService conversationService;
    private final CustomerConversationService customerConversationService;
    private final TaskService taskService;
    private final DocumentService documentService;
    private final JtbdService jtbdService;
    private final CustomerConversationContextService customerConversationContextService;
    private final CustomerChannelNotificationService customerChannelNotificationService;
    private final AssignmentService assignmentService;
    private final DocumentLinkService documentLinkService;
    private final RoutingService routingService;
    private final AuditService auditService;
    private final InboundMessageUnderstandingService inboundMessageUnderstandingService;

    @Transactional
    public InboundWhatsAppResult ingest(InboundWhatsAppRequest request) {
        String customerId = resolveCustomerId(request);
        identityResolutionService.registerLink(customerId, IdentifierType.PHONE, request.fromE164Phone());
        registerPolicyClaimHints(request, customerId);
        List<Task> openTasks = taskService.findOpenTasksForCustomer(customerId).stream()
                .map(taskResolutionService::resolveCanonical)
                .distinct()
                .toList();
        List<CustomerJtbd> activeJtbds = jtbdService.activeJtbdsForCustomer(customerId);
        boolean directReply = request.replyToWaMessageId() != null && !request.replyToWaMessageId().isBlank();

        if (Boolean.TRUE.equals(request.forceNewTask())) {
            return createNewTask(request, customerId, null);
        }

        if (isExplicitNewTaskRequest(request.bodyText())) {
            customerConversationContextService.clearPendingSelection(customerId, ChannelType.WHATSAPP);
            customerConversationContextService.clearActiveTask(customerId, ChannelType.WHATSAPP);
            List<CustomerConversationContextService.SelectionOption> options =
                    buildNewTaskOptions(customerId, activeJtbds);
            if (options.size() == 1 && isStandaloneReference(options.get(0).reference())) {
                return createNewTask(request, customerId, null);
            }
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.WHATSAPP, PendingSelectionType.TARGET, options);
            sendSelectionPrompt(request.fromE164Phone(), PendingSelectionType.TARGET, options);
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        Optional<Task> explicitTask = resolveTargetTask(request, customerId);
        if (explicitTask.isPresent()) {
            Task task = taskResolutionService.resolveCanonical(explicitTask.get());
            assertCustomerOwns(customerId, task);
            customerConversationContextService.setActiveTask(customerId, ChannelType.WHATSAPP, task.getTaskNumber());
            return appendToTask(task, customerId, request);
        }

        Optional<CustomerConversationContextService.SelectionMatch> selectionMatch =
                customerConversationContextService.matchPendingSelection(customerId, ChannelType.WHATSAPP, request.bodyText());
        if (selectionMatch.isPresent()) {
            customerConversationContextService.clearPendingSelection(customerId, ChannelType.WHATSAPP);
            if (isStandaloneReference(selectionMatch.get().option().reference())) {
                return createNewTask(request, customerId, null);
            }
            if (isTaskReference(selectionMatch.get().option().reference())) {
                Task task = taskService.loadCanonicalTask(stripReferencePrefix(selectionMatch.get().option().reference()));
                assertCustomerOwns(customerId, task);
                customerConversationContextService.setActiveTask(customerId, ChannelType.WHATSAPP, task.getTaskNumber());
                return appendToTask(task, customerId, request);
            }
            CustomerJtbd jtbd = jtbdService.loadCustomerJtbd(stripReferencePrefix(selectionMatch.get().option().reference()));
            if (!jtbd.getCustomerId().equals(customerId)) {
                throw new ValidationException("JTBD does not belong to resolved customer");
            }
            customerConversationContextService.setActiveJtbd(customerId, ChannelType.WHATSAPP, jtbd.getPublicId());
            return appendToConversation(customerId, jtbd, request, null);
        }

        Optional<CustomerConversationContextService.PendingSelection> pendingSelection =
                customerConversationContextService.pendingSelection(customerId, ChannelType.WHATSAPP);
        if (pendingSelection.isPresent()) {
            sendSelectionPrompt(request.fromE164Phone(), pendingSelection.get().type(), pendingSelection.get().options());
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        if (isSwitchToJtbdRequest(request.bodyText()) && !activeJtbds.isEmpty()) {
            if (activeJtbds.size() == 1) {
                customerConversationContextService.setActiveJtbd(customerId, ChannelType.WHATSAPP, activeJtbds.get(0).getPublicId());
                return appendToConversation(customerId, activeJtbds.get(0), request, null);
            }
            List<CustomerConversationContextService.SelectionOption> options = buildJtbdSelectionOptions(activeJtbds);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.WHATSAPP, PendingSelectionType.JTBD, options);
            sendSelectionPrompt(request.fromE164Phone(), PendingSelectionType.JTBD, options);
            return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
        }

        Optional<CustomerJtbd> activeJtbd = activeContextJtbd(customerId);
        if (activeJtbd.isPresent()) {
            InboundMessageUnderstandingService.InboundDecision decision =
                    inboundMessageUnderstandingService.analyze(
                            request.bodyText(),
                            request.claimIdHint(),
                            request.policyIdHint(),
                            request.attachmentUrls() != null && !request.attachmentUrls().isEmpty(),
                            activeJtbds);
            if (decision.createExpertTask()) {
                return createExpertTask(request, customerId, activeJtbd.get(), decision);
            }
            return appendToConversation(customerId, activeJtbd.get(), request, decision.assignedQueue());
        }

        InboundMessageUnderstandingService.InboundDecision decision =
                inboundMessageUnderstandingService.analyze(
                        request.bodyText(),
                        request.claimIdHint(),
                        request.policyIdHint(),
                        request.attachmentUrls() != null && !request.attachmentUrls().isEmpty(),
                        activeJtbds);
        if (decision.createNewJtbd()) {
            CustomerJtbd createdJtbd =
                    inboundMessageUnderstandingService.createCustomerJtbd(
                            customerId, decision.newJtbdTypeName(), jtbdService);
            customerConversationContextService.setActiveJtbd(customerId, ChannelType.WHATSAPP, createdJtbd.getPublicId());
            return decision.createExpertTask()
                    ? createExpertTask(request, customerId, createdJtbd, decision)
                    : appendToConversation(customerId, createdJtbd, request, decision.assignedQueue());
        }
        if (decision.matchedJtbd() != null) {
            customerConversationContextService.setActiveJtbd(
                    customerId, ChannelType.WHATSAPP, decision.matchedJtbd().getPublicId());
            return decision.createExpertTask()
                    ? createExpertTask(request, customerId, decision.matchedJtbd(), decision)
                    : appendToConversation(customerId, decision.matchedJtbd(), request, decision.assignedQueue());
        }

        if (!activeJtbds.isEmpty()) {
            if (!directReply && activeJtbds.size() > 1) {
                List<CustomerConversationContextService.SelectionOption> options = buildJtbdSelectionOptions(activeJtbds);
                customerConversationContextService.setPendingSelection(
                        customerId, ChannelType.WHATSAPP, PendingSelectionType.JTBD, options);
                sendSelectionPrompt(request.fromE164Phone(), PendingSelectionType.JTBD, options);
                return new InboundWhatsAppResult(null, null, InboundOutcome.PROMPTED);
            }
            if (activeJtbds.size() == 1) {
                customerConversationContextService.setActiveJtbd(customerId, ChannelType.WHATSAPP, activeJtbds.get(0).getPublicId());
                return appendToConversation(customerId, activeJtbds.get(0), request, decision.assignedQueue());
            }
        }

        return appendToConversation(customerId, null, request, decision.assignedQueue());
    }

    private Optional<Task> activeContextTask(String customerId) {
        return customerConversationContextService.activeTaskNumber(customerId, ChannelType.WHATSAPP)
                .flatMap(taskRepository::findByTaskNumber)
                .map(taskResolutionService::resolveCanonical)
                .filter(task -> task.getCustomerId().equals(customerId))
                .filter(task -> taskService.findOpenTasksForCustomer(customerId).stream()
                        .anyMatch(open -> open.getTaskNumber().equals(task.getTaskNumber())));
    }

    private Optional<CustomerJtbd> activeContextJtbd(String customerId) {
        return customerConversationContextService.activeCustomerJtbdPublicId(customerId, ChannelType.WHATSAPP)
                .map(jtbdService::loadCustomerJtbd)
                .filter(jtbd -> jtbd.getCustomerId().equals(customerId));
    }

    private InboundWhatsAppResult appendToTask(Task task, String customerId, InboundWhatsAppRequest request) {
        List<DocumentDto> documents = registerWhatsAppDocuments(task, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> urls = documents.stream().map(DocumentDto::fileUrl).toList();
        Map<String, Object> metadata = buildWaMetadata(request);
        if (!docIds.isEmpty()) {
            metadata.put("attachment_ids", docIds);
        }
        MessageDto message =
                conversationService.appendMessage(
                        task,
                        ChannelType.WHATSAPP,
                        SenderType.CUSTOMER,
                        request.fromE164Phone(),
                        request.bodyText(),
                        urls,
                        request.waMessageId(),
                        metadata);
        customerConversationContextService.setActiveTask(customerId, ChannelType.WHATSAPP, task.getTaskNumber());
        linkDocumentsToMessage(documents, message, task.getCustomerJtbd(), task);
        auditService.record(
                "INBOUND_WHATSAPP_APPENDED",
                "Task",
                task.getTaskNumber(),
                "SYSTEM",
                "whatsapp-adapter",
                Map.of("message_id", message.messageId()));
        return new InboundWhatsAppResult(task.getTaskNumber(), message.messageId(), InboundOutcome.APPENDED);
    }

    private InboundWhatsAppResult appendToConversation(
            String customerId, CustomerJtbd customerJtbd, InboundWhatsAppRequest request, String assignedGroup) {
        Conversation conversation = customerConversationService.getOrCreate(customerId, ChannelType.WHATSAPP);
        List<DocumentDto> documents = registerWhatsAppDocuments(conversation, customerJtbd, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> urls = documents.stream().map(DocumentDto::fileUrl).toList();
        Map<String, Object> metadata = buildWaMetadata(request);
        if (customerJtbd != null) {
            metadata.put("customer_jtbd_id", customerJtbd.getPublicId());
        }
        if (!docIds.isEmpty()) {
            metadata.put("attachment_ids", docIds);
        }
        MessageDto message =
                conversationService.appendMessage(
                        conversation,
                        customerJtbd,
                        null,
                        ChannelType.WHATSAPP,
                        SenderType.CUSTOMER,
                        request.fromE164Phone(),
                        request.bodyText(),
                        urls,
                        request.waMessageId(),
                        metadata);
        if (customerJtbd != null) {
            customerConversationContextService.setActiveJtbd(customerId, ChannelType.WHATSAPP, customerJtbd.getPublicId());
        } else {
            customerConversationContextService.clearActiveTask(customerId, ChannelType.WHATSAPP);
            customerConversationContextService.clearActiveJtbd(customerId, ChannelType.WHATSAPP);
        }
        linkDocumentsToMessage(documents, message, customerJtbd, null);
        auditService.record(
                "INBOUND_WHATSAPP_CONVERSATION_APPENDED",
                "Conversation",
                conversation.getPublicId(),
                "SYSTEM",
                "whatsapp-adapter",
                Map.of("message_id", message.messageId()));
        assignmentService.assign(
                conversation,
                messageRepository.findByPublicId(message.messageId()).orElse(null),
                assignedGroup != null ? assignedGroup : deriveAssignedGroup(customerJtbd),
                null);
        return new InboundWhatsAppResult(null, message.messageId(), InboundOutcome.APPENDED);
    }

    private InboundWhatsAppResult createExpertTask(
            InboundWhatsAppRequest request,
            String customerId,
            CustomerJtbd customerJtbd,
            InboundMessageUnderstandingService.InboundDecision decision) {
        Map<String, Object> metadata = buildWaMetadata(request);
        metadata.put("customer_jtbd_id", customerJtbd.getPublicId());
        metadata.put("jtbd_type", customerJtbd.getJtbdType().getName());
        Task task = taskService.createInternalTask(
                new CreateTaskRequest(
                        customerId,
                        decision.taskIssueType() != null ? decision.taskIssueType() : "expert_follow_up",
                        decision.domain().name().toLowerCase(Locale.ROOT),
                        blankToNull(request.claimIdHint()),
                        blankToNull(request.policyIdHint()),
                        TaskPriority.MEDIUM,
                        ChannelType.WHATSAPP,
                        request.bodyText(),
                        request.fromE164Phone(),
                        metadata,
                        request.waMessageId()),
                customerJtbd,
                TaskType.EXPERT_TASK,
                ExecutionTier.EXPERT,
                decision.assignedQueue());

        List<DocumentDto> documents = registerWhatsAppDocuments(task, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> urls = documents.stream().map(DocumentDto::fileUrl).toList();
        conversationService.enrichLatestMessageWithInboundFiles(task, urls, docIds);
        MessageDto latest = latestMessage(task);
        linkDocumentsToMessage(documents, latest, customerJtbd, task);
        customerConversationContextService.setActiveJtbd(customerId, ChannelType.WHATSAPP, customerJtbd.getPublicId());
        auditService.record(
                "INBOUND_WHATSAPP_EXPERT_TASK_CREATED",
                "Task",
                task.getTaskNumber(),
                "SYSTEM",
                "whatsapp-adapter",
                Map.of("customer_jtbd_id", customerJtbd.getPublicId()));
        return new InboundWhatsAppResult(task.getTaskNumber(), latest != null ? latest.messageId() : null, InboundOutcome.CREATED);
    }

    private MessageDto latestMessage(Task task) {
        List<MessageDto> timeline = conversationService.listTimeline(task);
        return timeline.isEmpty() ? null : timeline.get(timeline.size() - 1);
    }

    private InboundWhatsAppResult createNewTask(
            InboundWhatsAppRequest request, String customerId, CustomerJtbd customerJtbd) {
        Map<String, Object> metadata = buildWaMetadata(request);
        if (customerJtbd != null) {
            metadata.put("customer_jtbd_id", customerJtbd.getPublicId());
            metadata.put("jtbd_type", customerJtbd.getJtbdType().getName());
        }
        CreateTaskRequest create =
                new CreateTaskRequest(
                        customerId,
                        "whatsapp_inbound",
                        null,
                        blankToNull(request.claimIdHint()),
                        blankToNull(request.policyIdHint()),
                        TaskPriority.MEDIUM,
                        ChannelType.WHATSAPP,
                        request.bodyText(),
                        request.fromE164Phone(),
                        metadata,
                        request.waMessageId());
        TaskDto taskDto = taskService.createTask(create, customerJtbd);
        Task task =
                taskRepository
                        .findByTaskNumber(taskDto.taskId())
                        .map(taskResolutionService::resolveCanonical)
                        .orElseThrow(() -> new ValidationException("task not found after create"));

        List<DocumentDto> documents = registerWhatsAppDocuments(task, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> urls = documents.stream().map(DocumentDto::fileUrl).toList();
        conversationService.enrichLatestMessageWithInboundFiles(task, urls, docIds);
        linkDocumentsToMessage(documents, latestMessage(task), customerJtbd, task);
        customerConversationContextService.setActiveTask(customerId, ChannelType.WHATSAPP, task.getTaskNumber());

        auditService.record(
                "INBOUND_WHATSAPP_NEW_TICKET",
                "Task",
                taskDto.taskId(),
                "SYSTEM",
                "whatsapp-adapter",
                customerJtbd != null ? Map.of("customer_jtbd_id", customerJtbd.getPublicId()) : Map.of());
        return new InboundWhatsAppResult(taskDto.taskId(), null, InboundOutcome.CREATED);
    }

    private Optional<Task> resolveTargetTask(InboundWhatsAppRequest request, String customerId) {
        if (request.replyToWaMessageId() != null && !request.replyToWaMessageId().isBlank()) {
            Optional<Task> fromReplyContext = messageRepository
                    .findByExternalThreadRef(request.replyToWaMessageId().trim())
                    .map(com.omnichannel.support.domain.Message::getTask)
                    .map(taskResolutionService::resolveCanonical);
            if (fromReplyContext.isPresent()) {
                return fromReplyContext;
            }
        }
        if (request.taskNumberHint() != null && !request.taskNumberHint().isBlank()) {
            return taskRepository
                    .findByTaskNumber(request.taskNumberHint().trim().toUpperCase())
                    .map(taskResolutionService::resolveCanonical);
        }
        if (request.bodyText() != null) {
            java.util.regex.Matcher matcher = TICKET_NUMBER.matcher(request.bodyText());
            if (matcher.find()) {
                return taskRepository
                        .findByTaskNumber(matcher.group(1).toUpperCase())
                        .map(taskResolutionService::resolveCanonical);
            }
        }
        return Optional.empty();
    }

    private void sendSelectionPrompt(
            String phone,
            PendingSelectionType type,
            List<CustomerConversationContextService.SelectionOption> options) {
        try {
            customerChannelNotificationService.sendWhatsAppSelectionList(
                    phone,
                    promptIntro(type),
                    "Choose",
                    options.stream()
                            .map(option -> new MetaWhatsAppCloudApiClient.InteractiveListRow(
                                    option.reference(),
                                    interactiveTitle(option),
                                    interactiveDescription(option)))
                            .toList());
        } catch (ValidationException ex) {
            customerChannelNotificationService.send(
                    ChannelType.WHATSAPP,
                    phone,
                    null,
                    buildPromptBody(type, options));
        }
    }

    private static boolean isSwitchToTaskRequest(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("SWITCH REQUEST")
                || normalized.contains("CHANGE REQUEST")
                || normalized.contains("ANOTHER REQUEST")
                || normalized.contains("DIFFERENT REQUEST");
    }

    private static boolean isSwitchToJtbdRequest(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("SWITCH JOB")
                || normalized.contains("CHANGE JOB")
                || normalized.contains("ANOTHER JOB")
                || normalized.contains("DIFFERENT JOB")
                || normalized.contains("SWITCH JTBD")
                || normalized.contains("CHANGE JTBD");
    }

    private static boolean isExplicitNewTaskRequest(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("NEW REQUEST")
                || normalized.equals("CREATE REQUEST")
                || normalized.startsWith("NEW REQUEST ")
                || normalized.startsWith("CREATE REQUEST ");
    }

    private static List<CustomerConversationContextService.SelectionOption> buildTaskSelectionOptions(List<Task> openTasks) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(openTasks.size(), MAX_SELECTION_OPTIONS); i++) {
            Task task = openTasks.get(i);
            String label = task.getCustomerJtbd() != null
                    ? task.getCustomerJtbd().getJtbdType().getName() + " - " + task.getCustomerJtbd().getCurrentStage().getStageName()
                    : humanize(task.getIssueType()) + " - " + humanize(task.getStatus().name());
            if (task.getCustomerJtbd() != null) {
                label += " • request in progress";
            }
            options.add(new CustomerConversationContextService.SelectionOption(
                    i + 1,
                    "TASK:" + task.getTaskNumber(),
                    label));
        }
        return options;
    }

    private static List<CustomerConversationContextService.SelectionOption> buildJtbdSelectionOptions(List<CustomerJtbd> activeJtbds) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(activeJtbds.size(), MAX_SELECTION_OPTIONS); i++) {
            CustomerJtbd jtbd = activeJtbds.get(i);
            options.add(new CustomerConversationContextService.SelectionOption(
                    i + 1,
                    "JTBD:" + jtbd.getPublicId(),
                    jtbd.getJtbdType().getName() + " - " + jtbd.getCurrentStage().getStageName()));
        }
        return options;
    }

    private List<CustomerConversationContextService.SelectionOption> buildConversationTargetOptions(
            String customerId, List<Task> openTasks, List<CustomerJtbd> activeJtbds) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>(buildTaskSelectionOptions(openTasks));
        java.util.Set<Long> linkedJtbdIds = taskRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .filter(task -> task.getCustomerJtbd() != null)
                .map(task -> task.getCustomerJtbd().getId())
                .collect(java.util.stream.Collectors.toSet());
        int optionNumber = options.size() + 1;
        for (CustomerJtbd jtbd : activeJtbds) {
            if (linkedJtbdIds.contains(jtbd.getId()) || options.size() >= MAX_SELECTION_OPTIONS) {
                continue;
            }
            options.add(new CustomerConversationContextService.SelectionOption(
                    optionNumber++,
                    "JTBD:" + jtbd.getPublicId(),
                    jtbd.getJtbdType().getName() + " - " + jtbd.getCurrentStage().getStageName()));
        }
        return options;
    }

    private List<CustomerConversationContextService.SelectionOption> buildNewTaskOptions(
            String customerId, List<CustomerJtbd> activeJtbds) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>();
        java.util.Set<Long> linkedJtbdIds = taskRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .filter(task -> task.getCustomerJtbd() != null)
                .map(task -> task.getCustomerJtbd().getId())
                .collect(java.util.stream.Collectors.toSet());
        int optionNumber = 1;
        for (CustomerJtbd jtbd : activeJtbds) {
            if (linkedJtbdIds.contains(jtbd.getId()) || options.size() >= MAX_SELECTION_OPTIONS - 1) {
                continue;
            }
            options.add(new CustomerConversationContextService.SelectionOption(
                    optionNumber++,
                    "JTBD:" + jtbd.getPublicId(),
                    jtbd.getJtbdType().getName() + " - " + jtbd.getCurrentStage().getStageName()));
        }
        options.add(new CustomerConversationContextService.SelectionOption(
                optionNumber,
                "NEW:STANDALONE",
                "Something else - open a new request"));
        return options;
    }

    private static String buildPromptBody(
            PendingSelectionType type, List<CustomerConversationContextService.SelectionOption> options) {
        String topic = switch (type) {
            case TICKET -> "request";
            case JTBD -> "job";
            case TARGET -> "request";
        };
        StringBuilder builder = new StringBuilder("We found multiple ")
                .append(topic)
                .append(" options for your account. Reply with the number for the one you mean:\n");
        for (CustomerConversationContextService.SelectionOption option : options) {
            builder.append(option.optionNumber())
                    .append(". ")
                    .append(option.label())
                    .append(" [")
                    .append(option.reference())
                    .append("]\n");
        }
        builder.append("We will keep the conversation on that selection until you ask about another one.");
        return builder.toString();
    }

    private static String promptIntro(PendingSelectionType type) {
        return switch (type) {
            case TICKET -> "Please choose which request you want to discuss.";
            case JTBD -> "Please choose which job you want help with.";
            case TARGET -> "Please choose what you want to discuss or open a new request for.";
        };
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "Support request";
        }
        String normalized = value.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static boolean isTaskReference(String reference) {
        return reference != null && reference.startsWith("TASK:");
    }

    private static boolean isStandaloneReference(String reference) {
        return "NEW:STANDALONE".equals(reference);
    }

    private static String stripReferencePrefix(String reference) {
        if (reference == null) {
            return null;
        }
        int separator = reference.indexOf(':');
        if (separator < 0 || separator == reference.length() - 1) {
            return reference;
        }
        return reference.substring(separator + 1);
    }

    private static String interactiveTitle(CustomerConversationContextService.SelectionOption option) {
        if (isTaskReference(option.reference())) {
            return stripReferencePrefix(option.reference());
        }
        if (isStandaloneReference(option.reference())) {
            return "Something else";
        }
        String label = option.label();
        return label.length() <= 24 ? label : label.substring(0, 24);
    }

    private static String interactiveDescription(CustomerConversationContextService.SelectionOption option) {
        return option.label();
    }

    private void registerPolicyClaimHints(InboundWhatsAppRequest request, String customerId) {
        if (request.policyIdHint() != null && !request.policyIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.POLICY_ID, request.policyIdHint().trim());
        }
        if (request.claimIdHint() != null && !request.claimIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.CLAIM_ID, request.claimIdHint().trim());
        }
    }

    private List<DocumentDto> registerWhatsAppDocuments(Task task, String customerId, InboundWhatsAppRequest request) {
        if (request.attachmentUrls() == null || request.attachmentUrls().isEmpty()) {
            return List.of();
        }
        String claimHint = blankToNull(request.claimIdHint());
        String policyHint = blankToNull(request.policyIdHint());
        List<DocumentDto> documents = new ArrayList<>();
        int index = 0;
        for (String url : request.attachmentUrls()) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", "whatsapp_inbound");
            meta.put("attachment_index", index++);
            DriveFileToken token = DriveFileToken.parse(url);
            if (token != null) {
                meta.put("drive_file_id", token.fileId());
                if (token.fileName() != null && !token.fileName().isBlank()) {
                    meta.put("file_name", token.fileName());
                }
                if (token.mimeType() != null && !token.mimeType().isBlank()) {
                    meta.put("mime_type", token.mimeType());
                }
            }
            DocumentDto d =
                    documentService.register(
                            task,
                            customerId,
                            ChannelType.WHATSAPP,
                            url,
                            "whatsapp_attachment",
                            claimHint,
                            policyHint,
                            meta);
            documents.add(d);
        }
        return documents;
    }

    private List<DocumentDto> registerWhatsAppDocuments(
            Conversation conversation, CustomerJtbd customerJtbd, String customerId, InboundWhatsAppRequest request) {
        if (request.attachmentUrls() == null || request.attachmentUrls().isEmpty()) {
            return List.of();
        }
        String claimHint = blankToNull(request.claimIdHint());
        String policyHint = blankToNull(request.policyIdHint());
        List<DocumentDto> documents = new ArrayList<>();
        int index = 0;
        for (String url : request.attachmentUrls()) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", "whatsapp_inbound");
            meta.put("attachment_index", index++);
            DriveFileToken token = DriveFileToken.parse(url);
            if (token != null) {
                meta.put("drive_file_id", token.fileId());
                if (token.fileName() != null && !token.fileName().isBlank()) {
                    meta.put("file_name", token.fileName());
                }
                if (token.mimeType() != null && !token.mimeType().isBlank()) {
                    meta.put("mime_type", token.mimeType());
                }
            }
            documents.add(documentService.register(
                    conversation,
                    customerJtbd,
                    null,
                    customerId,
                    ChannelType.WHATSAPP,
                    url,
                    "whatsapp_attachment",
                    claimHint,
                    policyHint,
                    meta));
        }
        return documents;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static void assertCustomerOwns(String customerId, Task task) {
        if (!task.getCustomerId().equals(customerId)) {
            throw new ValidationException("task does not belong to resolved customer");
        }
    }

    private String deriveAssignedGroup(CustomerJtbd customerJtbd) {
        if (customerJtbd != null) {
            return taskRepository.findByCustomerJtbdIdOrderByCreatedAtDesc(customerJtbd.getId()).stream()
                    .map(Task::getAssignedQueue)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(routingService.resolveQueue(
                            RoutingContext.builder()
                                    .issueType(customerJtbd.getJtbdType().getName())
                                    .build()));
        }
        return routingService.resolveQueue(
                RoutingContext.builder()
                        .issueType("general_support_request")
                        .build());
    }

    private void linkDocumentsToMessage(
            List<DocumentDto> documents, MessageDto message, CustomerJtbd customerJtbd, Task task) {
        if (documents == null || documents.isEmpty() || message == null) {
            return;
        }
        var savedMessage = messageRepository.findByPublicId(message.messageId()).orElse(null);
        for (DocumentDto document : documents) {
            documentLinkService.link(
                    documentService.getByPublicId(document.documentId()),
                    null,
                    savedMessage,
                    customerJtbd,
                    task);
        }
    }

    private String resolveCustomerId(InboundWhatsAppRequest request) {
        if (request.customerIdHint() != null && !request.customerIdHint().isBlank()) {
            return request.customerIdHint().trim();
        }
        Optional<String> direct = identityResolutionService.resolveCustomerId(IdentifierType.PHONE, request.fromE164Phone());
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> mappedEmail = customerContactMappingService.counterpartForPhone(request.fromE164Phone());
        if (mappedEmail.isPresent()) {
            Optional<String> mappedCustomer = identityResolutionService.resolveCustomerId(IdentifierType.EMAIL, mappedEmail.get());
            if (mappedCustomer.isPresent()) {
                return mappedCustomer.get();
            }
        }
        return provisionCustomerForNewPhone(request.fromE164Phone());
    }

    private String provisionCustomerForNewPhone(String phone) {
        String newId = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        identityResolutionService.registerLink(newId, IdentifierType.PHONE, phone);
        return newId;
    }

    private static Map<String, Object> buildWaMetadata(InboundWhatsAppRequest request) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("wa_message_id", request.waMessageId());
        return meta;
    }

    private record DriveFileToken(String fileId, String fileName, String mimeType) {
        static DriveFileToken parse(String raw) {
            if (raw == null || !raw.startsWith("drive://")) {
                return null;
            }
            String remainder = raw.substring("drive://".length());
            String fileId = remainder;
            String fileName = null;
            String mimeType = null;
            int queryIndex = remainder.indexOf('?');
            if (queryIndex >= 0) {
                fileId = remainder.substring(0, queryIndex);
                String query = remainder.substring(queryIndex + 1);
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = java.net.URLDecoder.decode(part.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    if ("name".equals(key)) {
                        fileName = value;
                    } else if ("mime".equals(key)) {
                        mimeType = value;
                    }
                }
            }
            return new DriveFileToken(fileId, fileName, mimeType);
        }
    }

    public enum InboundOutcome {
        CREATED,
        APPENDED,
        PROMPTED
    }

    public record InboundWhatsAppResult(String taskNumber, String messageId, InboundOutcome outcome) {}
}
